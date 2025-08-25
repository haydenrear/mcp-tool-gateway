package com.hayden.mcptoolgateway.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.fn.RedeployFunction;
import com.hayden.utilitymodule.stream.StreamUtil;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.*;
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

import java.nio.file.Path;
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
    ToolGatewayConfigProperties toolGatewayConfigProperties;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    McpSyncServerDelegate mcpSyncServer;
    @Autowired
    SetClients setMcpClient;
    @Autowired
    Redeploy redeploy;

    private volatile boolean didInitialize = false;

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

    public record ToolCallbackDescriptor(ToolCallbackProvider provider, ToolCallback toolCallback) {}

    @Builder(toBuilder = true)
    public record McpServerToolState(
            List<ToolCallbackProvider> toolCallbackProviders,
            RedeployFunction.RedeployDescriptor lastDeploy) { }

    public enum DeployState {
        DEPLOY_SUCCESSFUL,
        DEPLOY_FAIL_NO_CONNECT_MCP,
        DEPLOY_FAIL,
        ROLLBACK_SUCCESSFUL,
        ROLLBACK_FAIL,
        ROLLBACK_FAIL_NO_CONNECT_MCP;

        public boolean didToolListChange() {
            return DEPLOY_SUCCESSFUL == this
                    || ROLLBACK_FAIL == this
                    || ROLLBACK_FAIL_NO_CONNECT_MCP == this;
        }

        public boolean didRedeploy() {
            return DEPLOY_SUCCESSFUL == this;
        }

        public boolean didRollback() {
            return ROLLBACK_SUCCESSFUL == this;
        }

    }

    @Builder(toBuilder = true)
    public record RedeployResult(
            @JsonProperty
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            Set<String> toolsRemoved,
            @JsonProperty
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            Set<String> toolsAdded,
            @JsonProperty("""
                    Tools that the deployed MCP server provided in list tools call
                    """)
            Set<String> tools,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("""
                    Error propagated for deploy
                    """)
            String deployErr,
            @JsonProperty("""
                    Path to find the deploy log
                    """) Path deployLog,
            @JsonProperty("""
                    Error propagated when attempting to run and connect to the MCP server
                    """)
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            String mcpConnectErr,
            @JsonProperty("""
                    The state of the deployment. The options are:
                    - DEPLOY_SUCCESSFUL
                        The deployment was successful and was able to connect to the new MCP server after deployment
                    - DEPLOY_FAIL_NO_CONNECT_MCP
                        The deployment failed because was not able to connect to the new MCP server after deployment
                    - DEPLOY_FAIL
                        The deployment failed to build the new MCP server
                    """)
            DeployState deployState,
            @JsonProperty("""
                    The state of the rollback. The options are:
                    - ROLLBACK_SUCCESSFUL
                        The deployment failed but was then able to rollback to the old version of the MCP server and connect to it.
                    - ROLLBACK_FAIL
                        The deployment failed and then the rollback also failed.
                    - ROLLBACK_FAIL_NO_CONNECT_MCP
                        The deployment failed and then was not able to connect to the old version of the MCP server.
                    """)
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            DeployState rollbackState,
            @JsonProperty("""
                    Provide any more information about the deployment
                    """)
            String deployMessage,
            @JsonProperty("""
                    Error happened during rollback.
                    """)
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            String rollbackErr,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Path deployedMcpServerLog
    ) {}

    @Builder(toBuilder = true)
    public record SetSyncClientResult(
            Set<String> tools,
            Set<String> toolsAdded,
            Set<String> toolsRemoved,
            String err,
            List<ToolCallbackProvider> providers) {
        public boolean wasSuccessful() {
            return StringUtils.isBlank(err);
        }
    }


    private final ReentrantReadWriteLock  lock = new ReentrantReadWriteLock();


    @PostConstruct
    public void init() {
        if (this.toolGatewayConfigProperties.isStartMcpServerOnInitialize())
            doPerformInit();
    }

    public void doPerformInit() {
        try {
            lock.writeLock().lock();
            buildTools();
            didInitialize = true;
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
                                .flatMap(s -> Stream.of(
                                        Map.entry(d.getKey(), McpServerToolState.builder().toolCallbackProviders(m.providers).build())));
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


    ToolDecoratorService.McpServerToolState getRedeploy(Map<String, ToolDecoratorService.McpServerToolState> newMcpServerState) {
        StringBuilder descriptions = new StringBuilder();

        for (var t : this.toolGatewayConfigProperties.getDeployableMcpServers().entrySet()) {
            var serverState = newMcpServerState.get(t.getKey());
            if (serverState == null || CollectionUtils.isEmpty(serverState.toolCallbackProviders())) {
                StringBuilder err = parseErr(serverState, t.getKey());

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
            String tools = StreamUtil.toStream(t.getValue().toolCallbackProviders())
                    .flatMap(tcp -> Arrays.stream(tcp.getToolCallbacks()))
                    .map(tc -> {
                        var td = """
                                - %s
                                """.formatted(tc.getToolDefinition().name());
                        return td;
                    })
                    .collect(Collectors.joining(System.lineSeparator()));

            descriptions.append("""
                    ## MCP Server Name
                    %s
                    
                    ### MCP Server Tools
                    %s
                    
                    """.formatted(t.getKey(), tools));

            if (t.getValue().lastDeploy != null && !t.getValue().lastDeploy().isSuccess()) {
                descriptions.append("""
                        ### MCP Server Error Information
                        """);
                if (StringUtils.isBlank(t.getValue().lastDeploy().err())) {
                    descriptions.append("""
                            Redeploy failed for MCP server last time with error: %s
                            """.formatted(t.getValue().lastDeploy().err()));
                }
                if (t.getValue().lastDeploy().log() != null
                    && t.getValue().lastDeploy().log().toFile().exists()) {
                    descriptions.append("""
                            If you would like to search through the log for the deploy, the file path is %s.
                            """.formatted(t.getValue().lastDeploy().log()));
                }

            }
        }

        StaticToolCallbackProvider redeployToolCallbackProvider = new StaticToolCallbackProvider(
                FunctionToolCallback
                        .<ToolModels.Redeploy, ToolDecoratorService.RedeployResult>builder(REDEPLOY_MCP_SERVER, (i, o) -> {
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
                                        return parseRedeployResult(i, toRedeploy);
                                    } else {
                                        return RedeployResult.builder()
                                                .deployErr("%s was not contained in set of deployable MCP servers %s - please update."
                                                        .formatted(i.deployService(), toolGatewayConfigProperties.getDeployableMcpServers().keySet()))
                                                .build();
                                    }
                                } else {
                                    return parseRedeployResult(i, toolGatewayConfigProperties.getDeployableMcpServers().get(i.deployService()));
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
                                
                                # Underlying MCP Servers and Tools that can be Redeployed, Along With Information About Deployments
                                
                                %s
                                """.formatted(descriptions.toString()))
                        .inputType(ToolModels.Redeploy.class)
                        .toolCallResultConverter((result, returnType) -> {
                            try {
                                return objectMapper.writeValueAsString(result);
                            } catch (JsonProcessingException e) {
                                return "Failed to process result %s with error getMessage %s"
                                        .formatted(returnType, e.getMessage());
                            }
                        })
                        .build());

        if (didInitialize) {
            mcpSyncServer.removeTool(REDEPLOY_MCP_SERVER);
        }

        mcpSyncServer.addTool(McpToolUtils.toSyncToolSpecification(redeployToolCallbackProvider.getToolCallbacks()[0]));
        mcpSyncServer.notifyToolsListChanged();

        return ToolDecoratorService.McpServerToolState.builder().toolCallbackProviders(Lists.newArrayList(redeployToolCallbackProvider)).build();
    }

    RedeployResult parseRedeployResult(ToolModels.Redeploy i, ToolGatewayConfigProperties.DeployableMcpServer toRedeploy) {
        if (!toolGatewayConfigProperties.getDeployableMcpServers().containsKey(i.deployService())) {
            return ToolDecoratorService.RedeployResult.builder()
                    .deployErr("%s was not contained in set of deployable MCP servers %s - please update."
                            .formatted(i.deployService(), toolGatewayConfigProperties.getDeployableMcpServers().keySet()))
                    .build();
        }
        var r = redeploy.doRedeploy(i, toRedeploy, this.mcpServerToolStates.remove(i.deployService()));
        this.mcpServerToolStates.put(i.deployService(), r.newToolState());

        if (r.didToolListChange()) {
            getRedeploy(this.mcpServerToolStates);
            mcpSyncServer.notifyToolsListChanged();
        }

        return r.redeployResult()
                .toBuilder()
                .deployLog(toRedeploy.getMcpDeployLog())
                .build();
    }


    @NotNull StringBuilder parseErr(ToolDecoratorService.McpServerToolState existing, String service) {
        StringBuilder err = new StringBuilder();

        boolean hasDeployErr = existing != null && existing.lastDeploy() != null && StringUtils.isNotBlank(existing.lastDeploy().err());
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
                    .append(existing.lastDeploy().err());
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



}
