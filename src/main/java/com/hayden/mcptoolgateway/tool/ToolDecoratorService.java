package com.hayden.mcptoolgateway.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.fn.RedeployFunction;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.result.Result;
import com.hayden.utilitymodule.stream.StreamUtil;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.*;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.StaticToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class ToolDecoratorService {

    public static final String REDEPLOY_MCP_SERVER = "redeploy-mcp-server";

    @Autowired
    DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;
    @Autowired
    ToolGatewayConfigProperties toolGatewayConfigProperties;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    RedeployFunction redeployFunction;
    @Autowired
    McpSyncServer mcpSyncServer;

    @Getter
    private final Map<String, McpServerToolState> toolCallbackProviders = new  ConcurrentHashMap<>();

    private final Map<String, DelegateMcpSyncClient> syncClients = new ConcurrentHashMap<>();

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class DelegateMcpSyncClient {
        @Delegate
        McpSyncClient client;

        /**
         * TODO: last error to return - or last error log file
         */
        String error;

        public DelegateMcpSyncClient(McpSyncClient client) {
            this.client = client;
        }

        public DelegateMcpSyncClient(String error) {
            this.error = error;
        }
    }

    @Builder(toBuilder = true)
    record CreateToolCallbackProviderResult(ToolCallbackProvider provider, McpSchema.Tool toolName, Exception e) { }


    @Builder(toBuilder = true)
    record McpServerToolState(
            List<ToolCallbackProvider> toolCallbackProviders,
            RedeployFunction.RedeployDescriptor lastDeploy) { }

    @Builder(toBuilder = true)
    record RedeployResult(
            Set<String> toolsRemoved,
            Set<String> toolsAdded,
            Set<String> tools,
            String deployErr,
            String mcpConnectErr
    ) {}

    @Builder(toBuilder = true)
    record SetSyncClientResult(Set<String> tools,
                               Set<String> toolsAdded,
                               Set<String> toolsRemoved,
                               String err,
                               List<ToolCallbackProvider> providers,
                               RedeployFunction.RedeployDescriptor lastDeploy) { }


    private final ReentrantReadWriteLock  lock = new ReentrantReadWriteLock();

    private volatile boolean addedRedeploy = false;

    @PostConstruct
    public void init() {
        try {
            lock.writeLock().lock();
            buildTools();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void buildTools() {
        Map<String, McpServerToolState> decoratedTools = toolGatewayConfigProperties
                .getDeployableMcpServers()
                .entrySet()
                .stream()
                .flatMap(d -> {
                    try {
                        var m = setMcpClient(d.getKey());
                        return Stream.of(Map.entry(d.getKey(), McpServerToolState.builder().toolCallbackProviders(m.providers).lastDeploy(m.lastDeploy).build()));
                    } catch (Exception e) {
                        log.error("Could not build MCP tools {} with {}.",
                                d.getKey(), e.getMessage(), e);
                        if (this.toolGatewayConfigProperties.isFailOnMcpClientInit())
                            throw e;

                        return Stream.empty();
                    }
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        this.toolCallbackProviders.putAll(addRedeployTools(decoratedTools));
    }


    private Map<String, McpServerToolState> addRedeployTools(Map<String, McpServerToolState> toolCallbackProviders) {
        StringBuilder descriptions = new StringBuilder();

        for (var t : this.toolGatewayConfigProperties.getDeployableMcpServers().entrySet()) {
            if (!toolCallbackProviders.containsKey(t.getKey()) || CollectionUtils.isEmpty(toolCallbackProviders.get(t.getKey()).toolCallbackProviders)) {
                var existing = toolCallbackProviders.get(t.getKey());
                StringBuilder err = parseErr(t, existing);

                descriptions.append("""
                        ## MCP Server Name
                        %s
                        
                        ## MCP Server Error Information
                        
                        This MCP server is not currently available or has no tools.
                        Please see the error below to understand why there is not tool information for this MCP server.
                        
                        %s
                        
                        """.formatted(t.getKey(), err));
            }
        }

        for (var t : toolCallbackProviders.entrySet())  {
            var tools = StreamUtil.toStream(t.getValue().toolCallbackProviders)
                    .flatMap(tcp -> Arrays.stream(tcp.getToolCallbacks()))
                    .map(tc -> {
                        var td = """
                                #### MCP Server Tool Name
                                %s
                                
                                #### MCP Server Tool Description
                                %s
                                """.formatted(tc.getToolDefinition().name(), tc.getToolDefinition().description());
                        return td;
                    })
                    .collect(Collectors.joining(System.lineSeparator()));

            descriptions.append("""
                    ## MCP Server Name
                    %s
                    
                    ### MCP Server Tools
                    
                    %s
                    """.formatted(t.getKey(), tools));
        }

        StaticToolCallbackProvider redeployToolCallbackProvider = new StaticToolCallbackProvider(
                FunctionToolCallback
                        .<ToolModels.Redeploy, RedeployResult>builder(REDEPLOY_MCP_SERVER, (i, o) -> {
                            try {
                                lock.writeLock().lock();
                                if (!toolGatewayConfigProperties.getDeployableMcpServers()
                                        .containsKey(i.deployService())) {
                                    log.error("MCP server name {} was not contained in options {}.",
                                            i.deployService(), toolGatewayConfigProperties.getDeployableMcpServers()
                                                    .keySet());
                                    if (toolGatewayConfigProperties.getDeployableMcpServers().size() == 1) {
                                        ToolGatewayConfigProperties.DeployableMcpServer toRedeploy = toolGatewayConfigProperties.getDeployableMcpServers()
                                                .entrySet()
                                                .stream()
                                                .findFirst()
                                                .orElseThrow()
                                                .getValue();
                                        log.error("Deploying only deployable MCP server with request - assuming mistake - redeploying existing {}.",
                                                toRedeploy.name());
                                        return doRedeploy(i,
                                                toRedeploy);
                                    } else {
                                        return RedeployResult.builder()
                                                .deployErr("%s was not contained in set of deployable MCP servers %s - please update."
                                                        .formatted(i.deployService(), toolGatewayConfigProperties.getDeployableMcpServers().keySet()))
                                                .build();
                                    }
                                } else {
                                    return doRedeploy(i, toolGatewayConfigProperties.getDeployableMcpServers()
                                            .get(i.deployService()));
                                }
                            } finally {
                                lock.writeLock().unlock();
                            }
                        })
                        .description("""
                                # Redeploy Tool Description
                                
                                This tool provides the ability to redeploy the underlying MCP servers and the underlying tools. Errors will be propagated.
                                
                                # Underlying MCP Servers and Tools that can be Redeployed
                                
                                %s
                                """.formatted(descriptions.toString()))
                        .inputType(ToolModels.Redeploy.class)
                        .build());

        if (addedRedeploy) {
            mcpSyncServer.removeTool(REDEPLOY_MCP_SERVER);
        }

        mcpSyncServer.addTool(McpToolUtils.toSyncToolSpecification(redeployToolCallbackProvider.getToolCallbacks()[0]));
        addedRedeploy = true;

        toolCallbackProviders.put(
                REDEPLOY_MCP_SERVER,
                McpServerToolState.builder().toolCallbackProviders(Lists.newArrayList(redeployToolCallbackProvider)).build());

        return toolCallbackProviders;
    }

    private @NotNull StringBuilder parseErr(Map.Entry<String, ToolGatewayConfigProperties.DeployableMcpServer> t,
                                            McpServerToolState existing) {
        StringBuilder err = new StringBuilder();

        boolean hasSyncErr = this.syncClients.containsKey(t.getKey()) && StringUtils.isNotBlank(this.syncClients.get(t.getKey()).error);
        boolean hasDeployErr = existing != null && existing.lastDeploy != null && StringUtils.isNotBlank(existing.lastDeploy.err());
        boolean hasMcpSyncClient = this.syncClients.containsKey(t.getKey()) && this.syncClients.get(t.getKey()).getClient() != null;
        boolean mcpServerAvailable = false;

        if (hasMcpSyncClient)
            mcpServerAvailable = isMcpServerAvailable(t);

        boolean hasMcpSyncClientConnected = hasMcpSyncClient && mcpServerAvailable;
        boolean hasMcpSyncClientNotConnected = hasMcpSyncClient && !mcpServerAvailable;

        if (hasSyncErr) {
            var s = this.syncClients.get(t.getKey()).error;
            err.append("""
                          ### MCP server connection error
                          
                          There was an error connecting to this MCP server
                          
                          %s
                          """)
                    .append(s);
        }

        if (hasDeployErr) {
            err.append("""
                          ### MCP server deployment error
                          
                          There was an error deploying this MCP server
                          
                          %s
                          """)
                    .append(existing.lastDeploy.err());
        }


        if (!hasDeployErr && !hasSyncErr && hasMcpSyncClientConnected) {
            log.error("Unknown failure - sync client available added but no tools.");
            err.append("""
                    ### MCP server unknown error
                    
                    MCP server seems to not have any tools - was able to ping the server.
                    """);
        }

        if (!hasDeployErr && !hasSyncErr && hasMcpSyncClientNotConnected) {
            log.error("Unknown failure - sync client not connected but sync client existing - a bug.");
            err.append("""
                    ### MCP server unknown error
                    
                    MCP server seems to have unknown connection error - a bug - was not able to ping the server.
                    """);
        }

        if (!hasDeployErr && !hasSyncErr && !this.syncClients.containsKey(t.getKey())) {
            log.error("Unknown connection failure - sync client not added.");
            err.append("""
                    ### MCP server unknown error
                    
                    MCP server connection unknown connection fail. No MCP server accessible.
                    """);
        }
        return err;
    }

    private boolean isMcpServerAvailable(Map.Entry<String, ToolGatewayConfigProperties.DeployableMcpServer> t) {
        try {
            this.syncClients.get(t.getKey()).getClient().ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static @NotNull String performedRedeployResult(ToolModels.Redeploy i) {
        return "performed redeploy of %s.".formatted(i.deployService());
    }


    private RedeployResult doRedeploy(ToolModels.Redeploy redeploy, ToolGatewayConfigProperties.DeployableMcpServer redeployMcpServer) {
        return this.dynamicMcpToolCallbackProvider.killClientAndThen(redeploy.deployService(), () -> {
            try {
                lock.writeLock().lock();
                var r = redeployFunction.performRedeploy(redeployMcpServer);

                if (!r.isSuccess()) {
                    log.debug("Failed to perform redeploy {}.", r);
                    var tc = createSetClientErr(new DynamicMcpToolCallbackProvider.McpError(r.err()), redeploy.deployService());
                    this.toolCallbackProviders.put(
                            redeploy.deployService(),
                            McpServerToolState.builder()
                                    .toolCallbackProviders(tc.providers)
                                    .lastDeploy(r)
                                    .build());
                    
                    return RedeployResult.builder()
                            .deployErr(performedRedeployResult(redeploy))
                            .toolsRemoved(tc.toolsRemoved)
                            .toolsAdded(tc.toolsAdded)
                            .tools(tc.tools)
                            .build();
                }

                SetSyncClientResult setSyncClientResult = setMcpClient(redeploy.deployService());

                McpServerToolState built = McpServerToolState.builder()
                        .toolCallbackProviders(setSyncClientResult.providers)
                        .lastDeploy(setSyncClientResult.lastDeploy)
                        .build();

                this.toolCallbackProviders.put(redeploy.deployService(), built);

                addRedeployTools(this.toolCallbackProviders);

                mcpSyncServer.notifyToolsListChanged();

                return handleConnectMcpError(redeploy, r, setSyncClientResult);
            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    private @NotNull SetSyncClientResult setMcpClient(String deployService) {
        return this.dynamicMcpToolCallbackProvider.buildClient(deployService)
                .map(m -> createSetSyncClient(m, deployService))
                .onErrorFlatMapResult(err -> Result.ok(createSetClientErr(err, deployService)))
                .unwrap();
    }

    private @NotNull RedeployResult handleConnectMcpError(ToolModels.Redeploy redeploy, 
                                                          RedeployFunction.RedeployDescriptor r, 
                                                          SetSyncClientResult setSyncClientResult) {
        if (!this.syncClients.containsKey(redeploy.deployService())) {
            return toRedeployRes(r, setSyncClientResult, "MCP client was not found for %s"
                    .formatted(redeploy.deployService()));
        } else if (this.syncClients.get(redeploy.deployService()).getClient() == null) {
            var err = this.syncClients.get(redeploy.deployService()).getError();
            if (err != null) {
                return toRedeployRes(r, setSyncClientResult,
                        "Error connecting to MCP client for %s after redeploy: %s".formatted(redeploy, err));
            } else {
                return toRedeployRes(r, setSyncClientResult,
                        "Unknown connecting to MCP client for %s after redeploy".formatted(redeploy));
            }
        } else {
            return toRedeployRes(r, setSyncClientResult,
                    "Performed redeploy for %s: %s.".formatted(redeploy.deployService(), r));
        }
    }

    private static RedeployResult toRedeployRes(RedeployFunction.RedeployDescriptor r, SetSyncClientResult setSyncClientResult,
                                                String mcpConnectErr) {
        return RedeployResult.builder()
                .mcpConnectErr(mcpConnectErr)
                .tools(setSyncClientResult.tools)
                .toolsRemoved(setSyncClientResult.toolsRemoved)
                .toolsAdded(setSyncClientResult.toolsAdded)
                .deployErr(r.err())
                .build();
    }


    private SetSyncClientResult createSetClientErr(DynamicMcpToolCallbackProvider.McpError m, String service) {
        this.syncClients.compute(service, (key, prev) -> {
            if (prev == null) {
                return new DelegateMcpSyncClient(m.getMessage());
            }

            prev.setClient(null);
            prev.setError(m.getMessage());
            return prev;
        });

        Set<String> removed = new HashSet<>();
        Set<String> tools = new HashSet<>();

        if (containsToolCallbackProviders(service)) {
            McpServerToolState mcpServerToolState = this.toolCallbackProviders.remove(service);
            for (var tcp : mcpServerToolState.toolCallbackProviders()) {
                for (var tc : tcp.getToolCallbacks()) {
                    mcpSyncServer.removeTool(tc.getToolDefinition().name());
                    removed.add(tc.getToolDefinition().name());
                }
            }

            this.toolCallbackProviders.put(
                    service,
                    mcpServerToolState
                            .toBuilder()
                            .toolCallbackProviders(null)
                            .lastDeploy(mcpServerToolState.lastDeploy)
                            .build());

            return SetSyncClientResult.builder()
                    .lastDeploy(mcpServerToolState.lastDeploy)
                    .toolsRemoved(removed)
                    .tools(tools)
                    .err(m.getMessage())
                    .build();
        }

        return SetSyncClientResult.builder()
                .tools(tools)
                .err(m.getMessage())
                .build();
    }


    private @NotNull SetSyncClientResult createSetSyncClient(McpSyncClient m, String deployService) {

        var mcpClient = this.syncClients.compute(deployService, (key, prev) -> {
            if (prev == null) {
                return new DelegateMcpSyncClient(m);
            }

            prev.setClient(m);
            prev.setError(null);
            return prev;
        });

        if (containsToolCallbackProviders(deployService)) {
            return updateExisting(m, deployService, mcpClient);
        } else {
            return createNew(m, deployService, mcpClient);
        }
    }

    private SetSyncClientResult createNew(McpSyncClient m, String deployService, DelegateMcpSyncClient mcpClient) {
        McpServerToolState removedState = this.toolCallbackProviders.remove(deployService);
        Map<String, ToolCallbackDescriptor> existing = toExistingToolCallbackProviders(removedState);

        if (!existing.isEmpty())
            log.error("Found exising tool callback providers! Impossible!");

        try {
            McpSchema.ListToolsResult listToolsResult = m.listTools();

            List<ToolCallbackProvider> providersCreated = new ArrayList<>();
            Set<String> toolsAdded = new HashSet<>();
            Set<String> toolsRemoved = new HashSet<>();
            Set<String> tools = new HashSet<>();
            Set<String> toolErrors = new HashSet<>();

            var t = toToolCallbackProvider(listToolsResult, mcpClient, deployService);
            for (var tcp : t) {
                if (tcp.provider == null) {
//                  there is no existing!
                    addToErr(tcp, toolErrors);
                } else {
                    Arrays.stream(tcp.provider.getToolCallbacks())
                            .forEach(tc -> mcpSyncServer.addTool(McpToolUtils.toSyncToolSpecification(tc)));
                    toolsAdded.add(tcp.toolName.name());
                    tools.add(tcp.toolName.name());
                    providersCreated.add(tcp.provider);
                }
            }

            return SetSyncClientResult.builder()
                    .toolsAdded(toolsAdded)
                    .toolsRemoved(toolsRemoved)
                    .lastDeploy(removedState.lastDeploy)
                    .tools(tools)
                    .providers(providersCreated)
                    .build();
        } catch (Exception e) {
            return parseToolExceptionFail(e, mcpClient, existing);
        }
    }

    private SetSyncClientResult updateExisting(McpSyncClient m, String deployService, DelegateMcpSyncClient mcpClient) {

        McpServerToolState removedState = this.toolCallbackProviders.remove(deployService);
        Map<String, ToolCallbackDescriptor> existing = toExistingToolCallbackProviders(removedState);

        try {
            McpSchema.ListToolsResult listToolsResult = m.listTools();

            List<ToolCallbackProvider> providersCreated = new ArrayList<>();
            Set<String>  toolsAdded = new HashSet<>();
            Set<String>  toolsRemoved = new HashSet<>();
            Set<String>  tools = new HashSet<>();
            Set<String> toolErrors = new HashSet<>();


            var newTools = listToolsResult.tools().stream()
                    .map(t -> Map.entry(t.name(), t))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            for (var n : newTools.entrySet()) {
                var p = createToolCallbackProvider(mcpClient, deployService, n.getValue());
                Optional.ofNullable(p.provider)
                        .ifPresentOrElse(tcp -> {
                            toolsAdded.add(p.toolName.name());
                            tools.add(p.toolName.name());
                            providersCreated.add(tcp);
                            Arrays.stream(tcp.getToolCallbacks())
                                    .forEach(tc -> mcpSyncServer.addTool(McpToolUtils.toSyncToolSpecification(tc)));
                        }, () -> {
                            log.error("Unable to create tool callback provider for {}.", n.getKey());
                            addToErr(p, toolErrors);
                        });
            }

            for (var n : existing.entrySet()) {
                if (!newTools.containsKey(n.getKey())) {
                    log.info("Removing tool {}", n.getKey());
                    mcpSyncServer.removeTool(n.getKey());
                    toolsRemoved.add(n.getKey());
                }
            }

            return SetSyncClientResult.builder()
                    .toolsAdded(toolsAdded)
                    .toolsRemoved(toolsRemoved)
                    .tools(tools)
                    .lastDeploy(removedState.lastDeploy)
                    .providers(providersCreated)
                    .build();
        } catch (Exception e) {
            return parseToolExceptionFail(e, mcpClient, existing);
        }
    }

    private boolean containsToolCallbackProviders(String deployService) {
        return this.toolCallbackProviders.containsKey(deployService)
                && !CollectionUtils.isEmpty(this.toolCallbackProviders.get(deployService).toolCallbackProviders);
    }


    private List<CreateToolCallbackProviderResult> toToolCallbackProvider(McpSchema.ListToolsResult listToolsResult,
                                                                          DelegateMcpSyncClient mcpSyncClient,
                                                                          String service) {
        return listToolsResult.tools().stream()
                .map(t -> createToolCallbackProvider(mcpSyncClient, service, t))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private @NotNull CreateToolCallbackProviderResult createToolCallbackProvider(DelegateMcpSyncClient mcpSyncClient,
                                                                                 String service, McpSchema.Tool t) {
        try {
            return CreateToolCallbackProviderResult.builder()
                    .toolName(t)
                    .provider(new StaticToolCallbackProvider(
                            FunctionToolCallback
                                    .builder(t.name(), (i, o) -> {
                                        try {
                                            lock.readLock().lock();
                                            if (mcpSyncClient.client == null) {
                                                var last = this.toolCallbackProviders.get(service).lastDeploy();
                                                return "Attempted to call tool %s with MCP client err %s and deploy err %s."
                                                        .formatted(t.name(), mcpSyncClient.error, last);
                                            }
                                            var tc = objectMapper.writeValueAsString(i);
                                            return mcpSyncClient.callTool(new McpSchema.CallToolRequest(t.name(), tc));
                                        } catch (JsonProcessingException e) {
                                            log.error("Error performing tool call {}", e.getMessage(), e);
                                            return "Could not process JSON result %s from tool %s with err %s."
                                                    .formatted(String.valueOf(i), t.name(), e.getMessage());
                                        } finally {
                                            lock.readLock().unlock();
                                        }
                                    })
                                    .description(t.description())
                                    .inputSchema(getInputSchema(t))
                                    .build()))
                    .build();
        } catch (JsonProcessingException e) {
            log.error("Error resolving  tool callback provider for tools {}", t.name(), e);
            return CreateToolCallbackProviderResult.builder()
                    .e(e)
                    .build();
        }
    }

    private String getInputSchema(McpSchema.Tool t) throws JsonProcessingException {
        return objectMapper.writeValueAsString(t.inputSchema());
    }


    private static SetSyncClientResult parseToolExceptionFail(Exception e, DelegateMcpSyncClient mcpClient, Map<String, ToolCallbackDescriptor> existing) {
        log.error("Error when attempting to retrieve tools: {}", e.getMessage(), e);
        mcpClient.setError(e.getMessage());
        return SetSyncClientResult.builder()
                .toolsRemoved(existing.keySet())
                .err(e.getMessage())
                .build();
    }

    public record ToolCallbackDescriptor(ToolCallbackProvider provider, ToolCallback toolCallback) {}

    private static @NotNull HashMap<String, ToolCallbackDescriptor> toExistingToolCallbackProviders(McpServerToolState removedState) {
        List<ToolCallbackProvider> removedProviders = Optional.ofNullable(removedState.toolCallbackProviders()).orElse(new ArrayList<>());

        var existing = new HashMap<String, ToolCallbackDescriptor>();

        for (int i =0; i < removedProviders.size(); i++) {
            var provider = removedProviders.get(i);
            for (ToolCallback toolCallback : provider.getToolCallbacks()) {
                existing.put(toolCallback.getToolDefinition().name(), new ToolCallbackDescriptor(provider, toolCallback));
            }
        }
        return existing;
    }

    private static void addToErr(CreateToolCallbackProviderResult p, Set<String> toolErrors) {
        if (p.e == null) {
            toolErrors.add("Unknown error creating tool callback for %s".formatted(p.toolName.name()));
        } else {
            toolErrors.add("Error creating tool callback for %s: %s".formatted(p.toolName.name(), p.e.getMessage()));
        }
    }
}
