package com.hayden.mcptoolgateway.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.fn.RedeployFunction;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    McpSyncServerDelegate mcpSyncServer;
    @Autowired
    SetClients setMcpClient;

    private final Map<String, McpServerToolState> mcpServerToolStates = new  ConcurrentHashMap<>();


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class DelegateMcpSyncClient {
        McpSyncClient client;

        public synchronized McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest) {
            return client.callTool(callToolRequest);
        }

        /**
         * TODO: last error to return - or last error log file
         */
        String error;

        public synchronized void setClient(McpSyncClient client) {
            this.client = client;
        }

        public synchronized void setError(String error) {
            this.error = error;
        }

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
            String mcpConnectErr,
            boolean didRollback
    ) {}

    @Builder(toBuilder = true)
    record SetSyncClientResult(Set<String> tools,
                               Set<String> toolsAdded,
                               Set<String> toolsRemoved,
                               String err,
                               List<ToolCallbackProvider> providers,
                               RedeployFunction.RedeployDescriptor lastDeploy) {
        boolean wasSuccessful() {
            return StringUtils.isBlank(err);
        }
    }


    private final ReentrantReadWriteLock  lock = new ReentrantReadWriteLock();

    private volatile boolean didInitialize = false;

    @PostConstruct
    public void init() {
        try {
            lock.writeLock().lock();
            buildTools();
            didInitialize = true;
            mcpSyncServer.notifyToolsListChanged();
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
                        var m = setMcpClient.setMcpClient(d.getKey(), McpServerToolState.builder().build());
                        return Optional.ofNullable(m)
                                .stream()
                                .flatMap(s -> Stream.of(Map.entry(d.getKey(), McpServerToolState.builder().toolCallbackProviders(m.providers).lastDeploy(m.lastDeploy).build())));
                    } catch (Exception e) {
                        log.error("Could not build MCP tools {} with {}.",
                                d.getKey(), e.getMessage(), e);
                        if (this.toolGatewayConfigProperties.isFailOnMcpClientInit())
                            throw e;

                        return Stream.empty();
                    }
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        this.mcpServerToolStates.putAll(decoratedTools);
        this.mcpServerToolStates.put(REDEPLOY_MCP_SERVER, getRedeploy(decoratedTools));
    }


    private McpServerToolState getRedeploy(Map<String, McpServerToolState> newMcpServerState) {
        StringBuilder descriptions = new StringBuilder();

        for (var t : this.toolGatewayConfigProperties.getDeployableMcpServers().entrySet()) {
            if (!newMcpServerState.containsKey(t.getKey())) {
                descriptions.append("""
                        ## MCP Server Name
                        %s
                        
                        ## MCP Server Error Information
                        
                        This MCP server is not currently available or has no tools.
                        """.formatted(t.getKey()));
            }
            if (CollectionUtils.isEmpty(newMcpServerState.get(t.getKey()).toolCallbackProviders)) {
                StringBuilder err = parseErr(newMcpServerState.get(t.getKey()), t.getKey());

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

        for (var t : newMcpServerState.entrySet())  {
            String tools = StreamUtil.toStream(t.getValue().toolCallbackProviders)
                        .flatMap(tcp -> Arrays.stream(tcp.getToolCallbacks()))
                        .map(tc -> {
                            var td = """
                                - %s.%s
                                """.formatted(t.getKey(), tc.getToolDefinition().name());
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
                                            i.deployService(), toolGatewayConfigProperties.getDeployableMcpServers().keySet());
                                    if (toolGatewayConfigProperties.getDeployableMcpServers().size() == 1) {
                                        ToolGatewayConfigProperties.DeployableMcpServer toRedeploy = toolGatewayConfigProperties.getDeployableMcpServers()
                                                .entrySet().stream()
                                                .findFirst().orElseThrow()
                                                .getValue();
                                        log.error("Deploying only deployable MCP server with request - assuming mistake - redeploying existing {}.",
                                                toRedeploy.name());
                                        return doRedeploy(i, toRedeploy);
                                    } else {
                                        return RedeployResult.builder()
                                                .deployErr("%s was not contained in set of deployable MCP servers %s - please update."
                                                        .formatted(i.deployService(), toolGatewayConfigProperties.getDeployableMcpServers().keySet()))
                                                .build();
                                    }
                                } else {
                                    return doRedeploy(i, toolGatewayConfigProperties.getDeployableMcpServers().get(i.deployService()));
                                }
                            } finally {
                                lock.writeLock().unlock();
                            }
                        })
                        .description("""
                                # Redeploy Tool Description
                                
                                This tool provides the ability to redeploy the underlying MCP servers and the underlying tools.
                                Errors will be provided below so that you can make changes and redeploy again.
                                If there is an issue with redeploy and the tool is able, then it will be rolled back to the previous version
                                and the error will be provided below.
                                
                                # Underlying MCP Servers and Tools that can be Redeployed
                                
                                %s
                                """.formatted(descriptions.toString()))
                        .inputType(ToolModels.Redeploy.class)
                        .toolCallResultConverter((result, returnType) -> {
                            try {
                                return objectMapper.writeValueAsString(result);
                            } catch (JsonProcessingException e) {
                                return "Failed to process result %s with error message %s"
                                        .formatted(returnType, e.getMessage());
                            }
                        })
                        .build());

        if (didInitialize) {
            mcpSyncServer.removeTool(REDEPLOY_MCP_SERVER);
        }

        mcpSyncServer.addTool(McpToolUtils.toSyncToolSpecification(redeployToolCallbackProvider.getToolCallbacks()[0]));

        return McpServerToolState.builder().toolCallbackProviders(Lists.newArrayList(redeployToolCallbackProvider)).build();
    }

    private @NotNull StringBuilder parseErr(McpServerToolState existing, String service) {
        StringBuilder err = new StringBuilder();

        boolean hasDeployErr = existing != null && existing.lastDeploy != null && StringUtils.isNotBlank(existing.lastDeploy.err());
        boolean hasSyncErr = setMcpClient.clientHasError(service);
        boolean hasMcpSyncClient = setMcpClient.hasClient(service);
        boolean mcpServerAvailable = false;

        if (hasMcpSyncClient)
            mcpServerAvailable = setMcpClient.isMcpServerAvailable(service);

        boolean hasMcpSyncClientConnected = hasMcpSyncClient && mcpServerAvailable;
        boolean hasMcpSyncClientNotConnected = hasMcpSyncClient && !mcpServerAvailable;

        if (hasSyncErr) {
            var s = setMcpClient.getError(service);
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

        if (!hasDeployErr && !hasSyncErr && setMcpClient.noClientKey(service)) {
            log.error("Unknown connection failure - sync client not added.");
            err.append("""
                    ### MCP server unknown error
                    
                    MCP server connection unknown connection fail. No MCP server accessible.
                    """);
        }
        return err;
    }

    private static @NotNull String redeployFailedErr(ToolModels.Redeploy i) {
        return "Error performing redeploy of %s.".formatted(i.deployService());
    }

    private static @NotNull String performedRedeployResultRollback(ToolModels.Redeploy i) {
        return "Tried to redeploy %s, redeploy failed and rolled back to previous version.".formatted(i.deployService());
    }


    private RedeployResult doRedeploy(ToolModels.Redeploy redeploy, ToolGatewayConfigProperties.DeployableMcpServer redeployMcpServer) {
        var res = this.dynamicMcpToolCallbackProvider.killClientAndThen(redeploy.deployService(), () -> {
            try {
                lock.writeLock().lock();
                var d = this.toolGatewayConfigProperties.getDeployableMcpServers().get(redeploy.deployService());

                boolean doRollback = prepareRollback(d);

                var r = redeployFunction.performRedeploy(redeployMcpServer);

                if (!r.isSuccess()) {
                    log.debug("Failed to perform redeploy {} - copying old artifact and restarting.", r);
                    if (doRollback) {
                        return rollback(redeploy, d, r);
                    }

                    return handleFailedRedeployNoRollback(redeploy, r);
                }

                return handleDidRedeployUpdateToolCallbackProviders(redeploy, r);
            } finally {
                lock.writeLock().unlock();
            }
        });

        if (!res.didRollback)
            mcpSyncServer.notifyToolsListChanged();

        return res;
    }

    private RedeployResult rollback(ToolModels.Redeploy redeploy,
                                    ToolGatewayConfigProperties.DeployableMcpServer d,
                                    RedeployFunction.RedeployDescriptor r) {
        try {
            if (tryRollback(redeploy, d, r)) {
                return handleDidRedeployUpdateToolCallbackProviders(redeploy, r)
                        .toBuilder()
                        .deployErr(performedRedeployResultRollback(redeploy))
                        .didRollback(true)
                        .build();
            } else {
                RedeployResult redeployResult = handleFailedRedeployNoRollback(redeploy, r);
                return redeployResult.toBuilder()
                        .deployErr("Deploy err: %s - tried to rollback but failed with unknown error."
                                .formatted(redeployResult.deployErr))
                        .build();
            }
        } catch (IOException e) {
            log.error("Failed to copy MCP server artifact back from cache - failed to rollback to previous version.");
            RedeployResult redeployResult = handleFailedRedeployNoRollback(redeploy, r);
            return redeployResult.toBuilder()
                    .deployErr("Deploy err: %s - tried to rollback but failed with err: %s"
                            .formatted(redeployResult.deployErr, e.getMessage()))
                    .build();
        }
    }

    private boolean prepareRollback(ToolGatewayConfigProperties.DeployableMcpServer d) {
        boolean doRollback = false;

        if (d.binary().toFile().exists()) {
            try {
                Files.copy(
                        d.binary(),
                        toolGatewayConfigProperties.getArtifactCache().resolve(d.binary().toFile().getName()),
                        StandardCopyOption.REPLACE_EXISTING);
                doRollback = true;
            } catch (IOException e) {
                log.error("Failed to copy MCP server artifact to cache.");
            }
        }
        return doRollback;
    }

    private boolean tryRollback(ToolModels.Redeploy redeploy, ToolGatewayConfigProperties.DeployableMcpServer d, RedeployFunction.RedeployDescriptor r) throws IOException {
        Files.copy(
                toolGatewayConfigProperties.getArtifactCache().resolve(d.binary().toFile().getName()),
                d.binary(),
                StandardCopyOption.REPLACE_EXISTING);

        var toSet = setMcpClient.setMcpClient(redeploy.deployService(), this.mcpServerToolStates.get(redeploy.deployService()));

        if (toSet.wasSuccessful() && setMcpClient.clientInitialized(redeploy.deployService())) {
            this.mcpServerToolStates.put(redeploy.deployService(), McpServerToolState.builder()
                            .toolCallbackProviders(toSet.providers)
                            .lastDeploy(r)
                            .build());

            return true;
        }
        return false;
    }

    private RedeployResult handleFailedRedeployNoRollback(ToolModels.Redeploy redeploy, RedeployFunction.RedeployDescriptor r) {
        var tc = setMcpClient.createSetClientErr(
                redeploy.deployService(),
                new DynamicMcpToolCallbackProvider.McpError(r.err()),
                this.mcpServerToolStates.remove(redeploy.deployService()));

        this.mcpServerToolStates.put(
                redeploy.deployService(),
                McpServerToolState.builder()
                        .toolCallbackProviders(tc.providers)
                        .lastDeploy(r)
                        .build());

        return RedeployResult.builder()
                .deployErr(redeployFailedErr(redeploy))
                .toolsRemoved(tc.toolsRemoved)
                .toolsAdded(tc.toolsAdded)
                .tools(tc.tools)
                .build();
    }

    private @NotNull RedeployResult handleDidRedeployUpdateToolCallbackProviders(ToolModels.Redeploy redeploy, RedeployFunction.RedeployDescriptor r) {
        SetSyncClientResult setSyncClientResult = setMcpClient.setMcpClient(redeploy.deployService(), this.mcpServerToolStates.remove(redeploy.deployService()));

        McpServerToolState built = McpServerToolState.builder()
                .toolCallbackProviders(setSyncClientResult.providers)
                .lastDeploy(setSyncClientResult.lastDeploy)
                .build();

        this.mcpServerToolStates.put(redeploy.deployService(), built);

        this.mcpServerToolStates.put(REDEPLOY_MCP_SERVER, getRedeploy(this.mcpServerToolStates));

        return handleConnectMcpError(redeploy, r, setSyncClientResult);
    }


    private @NotNull RedeployResult handleConnectMcpError(ToolModels.Redeploy redeploy,
                                                          RedeployFunction.RedeployDescriptor r,
                                                          SetSyncClientResult setSyncClientResult) {
        if (setMcpClient.noClientKey(redeploy.deployService())) {
            return toRedeployRes(r, setSyncClientResult, "MCP client was not found for %s"
                    .formatted(redeploy.deployService()));
        } else if (setMcpClient.noMcpClient(redeploy.deployService())) {
            var err = setMcpClient.getError(redeploy.deployService());
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



    public record ToolCallbackDescriptor(ToolCallbackProvider provider, ToolCallback toolCallback) {}

}
