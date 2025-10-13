package com.hayden.mcptoolgateway.tool.deploy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.mcptoolgateway.tool.*;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import com.hayden.utilitymodule.stream.StreamUtil;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.McpAsyncClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.StaticToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedeployToolDecorator implements ToolDecorator {

    private final ToolGatewayConfigProperties toolGatewayConfigProperties;

    private final McpServerToolStates ts;

    private final ObjectMapper objectMapper;

    private final Redeploy redeploy;

    @Override
    public ToolDecoratorToolStateUpdate decorate(ToolDecoratorState newMcpServerState) {
        if (newMcpServerState.newMcpServerState().values().stream().anyMatch(c -> !CollectionUtils.isEmpty(c.added())))  {
            throw new RuntimeException("Attempted to do redeploy with tool state deployed with multiple replicas. Not allowed.");
        }
        return new ToolDecoratorToolStateUpdate.AddToolStateUpdate(RedeployFunction.REDEPLOY_MCP_SERVER, getRedeploy(newMcpServerState.newMcpServerState()));
    }

    public ToolDecoratorToolStateUpdate decorate(Map<String, ToolDecoratorService.McpServerToolState> newMcpServerState) {
        return decorate(new ToolDecoratorState(newMcpServerState, new ArrayList<>()));
    }

    @Override
    public boolean isEnabled() {
        var is =  toolGatewayConfigProperties.isEnableRedeployable();
        if (!is)
            log.info("Skipping addition of redeploy tools as enable redeployable property set to false.");

        return is;
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

            if (t.getValue().lastDeploy() != null && !t.getValue().lastDeploy().isSuccess()) {
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
                        .<ToolModels.Redeploy, DeployModels.RedeployResult>builder(RedeployFunction.REDEPLOY_MCP_SERVER, (i, o) -> ts.doOverWriteState(() -> {
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
                                    return DeployModels.RedeployResult.builder()
                                            .deployErr("%s was not contained in set of deployable MCP servers %s - please update."
                                                    .formatted(i.deployService(), toolGatewayConfigProperties.getDeployableMcpServers().keySet()))
                                            .build();
                                }
                            } else {
                                return doRedeploy(i, toolGatewayConfigProperties.getDeployableMcpServers().get(i.deployService()));
                            }
                        }))
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
                            } catch (
                                    JsonProcessingException e) {
                                return "Failed to process result %s with error getMessage %s"
                                        .formatted(returnType, e.getMessage());
                            }
                        })
                        .build());

        if (ts.isInitialized()) {
            ts.removeTool(RedeployFunction.REDEPLOY_MCP_SERVER);
        }

        ts.addTool(McpToolUtils.toSyncToolSpecification(redeployToolCallbackProvider.getToolCallbacks()[0]));
        ts.notifyToolsListChanged();

        return ToolDecoratorService.McpServerToolState.builder()
                .toolCallbackProviders(Lists.newArrayList(redeployToolCallbackProvider))
                .build();
    }

    DeployModels.RedeployResult doRedeploy(ToolModels.Redeploy i,
                                           ToolGatewayConfigProperties.DeployableMcpServer toRedeploy) {
        if (!toolGatewayConfigProperties.getDeployableMcpServers().containsKey(i.deployService())) {
            return DeployModels.RedeployResult.builder()
                    .deployErr("%s was not contained in set of deployable MCP servers %s - please update."
                            .formatted(i.deployService(), toolGatewayConfigProperties.getDeployableMcpServers().keySet()))
                    .build();
        }

        var r = redeploy.doRedeploy(new ToolDecoratorInterpreter.ToolDecoratorEffect.DoRedeploy(i, toRedeploy, this.ts.removeToolState(i.deployService())));

        this.ts.addUpdateToolState(i.deployService(), r.newToolState());

        if (r.didToolListChange()) {
            var redeployed = this.decorate(new ToolDecoratorState(this.ts.copyOf(), new ArrayList<>()));
            this.ts.addUpdateToolState(redeployed);
            ts.notifyToolsListChanged();
        }

        return r.redeployResult()
                .toBuilder()
                .deployLog(toRedeploy.getMcpDeployLog())
                .build();
    }


    @NotNull
    StringBuilder parseErr(ToolDecoratorService.McpServerToolState existing, String service) {
        StringBuilder err = new StringBuilder();

        var d = new McpServerToolStates.DeployedService(service, ToolDecoratorService.SYSTEM_ID);

        boolean hasDeployErr = existing != null && existing.lastDeploy() != null && StringUtils.isNotBlank(existing.lastDeploy().err());
        boolean hasSyncErr = ts.clientHasError(service);
        boolean hasMcpSyncClient = ts.hasClient(service);
        boolean mcpServerAvailable = false;

        if (hasMcpSyncClient)
            mcpServerAvailable = ts.isMcpServerAvailable(service);

        boolean hasMcpSyncClientConnected = hasMcpSyncClient && mcpServerAvailable;
        boolean hasMcpSyncClientNotConnected = hasMcpSyncClient && !mcpServerAvailable;

        if (hasSyncErr) {
            var s = ts.getError(service);
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

        if (!hasDeployErr && !hasSyncErr && ts.noClientKey(service)) {
            log.error("Unknown connection failure - sync client not added.");
            err.append("""
                    ### MCP server unknown error
                    
                    MCP server connection unknown connection fail. No MCP server accessible.
                    """);
        }
        return err;
    }


}
