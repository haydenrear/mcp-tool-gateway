package com.hayden.mcptoolgateway.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.tool.StaticToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class ToolDecoratorService {

    @Autowired
    DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;
    @Autowired
    ToolGatewayConfigProperties toolGatewayConfigProperties;
    @Autowired
    ObjectMapper objectMapper;


    @Getter
    Map<String, List<ToolCallbackProvider>> toolCallbackProviders;

    private final ConcurrentHashMap<String, DelegateMcpSyncClient> syncClients = new ConcurrentHashMap<>();

    private final ReentrantReadWriteLock  lock = new ReentrantReadWriteLock();

    @PostConstruct
    public void init() {
        buildTools();
    }

    private void buildTools() {
        Map<String, List<ToolCallbackProvider>> decoratedTools = toolGatewayConfigProperties
                .getDeployableMcpServers()
                .entrySet()
                .stream()
                .flatMap(d -> {
                    try {
                        return this.dynamicMcpToolCallbackProvider.buildClient(d.getKey())
                                                                  .map(m -> createSetSyncClient(m, d.getKey()))
                                                                  .toStream()
                                                                  .flatMap(Collection::stream)
                                                                  .map(tc -> Map.entry(d.getKey(), tc));
                    } catch (Exception e) {
                        log.error("Could not build MCP tools {} with {}.",
                                d.getKey(), e.getMessage(), e);
                        if (this.toolGatewayConfigProperties.isFailOnMcpClientInit())
                            throw e;

                        return Stream.empty();
                    }
                })
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        // TODO: add redeploy tools - easy to add.
        this.toolCallbackProviders = addRedeployTools(decoratedTools);
    }

    public record Redeploy(@JsonProperty("Service to redeploy") String deployService) {}

    private Map<String, List<ToolCallbackProvider>> addRedeployTools(Map<String, List<ToolCallbackProvider>> toolCallbackProviders) {
        toolCallbackProviders.put("redeploy-mcp-server", List.of(
                new StaticToolCallbackProvider(
                        FunctionToolCallback
                                .<Redeploy, String>builder("redeploy-mcp-server", (i, o) -> {
                                    try {
                                        lock.writeLock().lock();
                                        if (!toolGatewayConfigProperties.getDeployableMcpServers()
                                                                        .containsKey(i.deployService)) {
                                            log.error("MCP server name {} was not contained in options {}.",
                                                    i.deployService, toolGatewayConfigProperties.getDeployableMcpServers()
                                                                                                .keySet());
                                            if (toolGatewayConfigProperties.getDeployableMcpServers()
                                                                           .size() == 1) {
                                                log.error("Deploying only deployable MCP server with request - assuming mistake.");
                                                return doRedeploy(i, toolGatewayConfigProperties.getDeployableMcpServers()
                                                                                                .entrySet()
                                                                                                .stream()
                                                                                                .findFirst()
                                                                                                .orElseThrow()
                                                                                                .getValue());
                                            } else {
                                                return "%s was not contained in set of deployable MCP servers %s - please update."
                                                        .formatted(i.deployService, toolGatewayConfigProperties.getDeployableMcpServers()
                                                                                                               .keySet());
                                            }
                                        } else {
                                            return doRedeploy(i, toolGatewayConfigProperties.getDeployableMcpServers()
                                                                                            .get(i.deployService));
                                        }
                                    } finally {
                                        lock.writeLock().unlock();
                                    }
                                })
                                .description("Redeploy the %s underlying MCP server - should be used if you've made changes to the code for this server.")
                                .inputType(Redeploy.class)
                                .build())));
        return toolCallbackProviders;
    }

    private static @NotNull String performedRedeployResult(Redeploy i) {
        return "performed redeploy of %s.".formatted(i.deployService);
    }

    public String doRedeploy(Redeploy redeploy, ToolGatewayConfigProperties.DeployableMcpServer redeployMcpServer) {
        return this.dynamicMcpToolCallbackProvider.killClientAndThen(redeploy.deployService, () -> {
            try {
                this.toolCallbackProviders.remove(redeploy.deployService);
//              TODO: in the event that this fails, add the location of the file where the error can be found
                var p = new ProcessBuilder(redeployMcpServer.deployCommand())
                        .directory(redeployMcpServer.directory().toFile())
                        .redirectErrorStream(true)
                        .start();

                // TODO: write res ? probably not ... this should read the
                var res = p.waitFor();

                this.toolCallbackProviders.put(
                        redeploy.deployService,
                        this.dynamicMcpToolCallbackProvider.buildClient(redeploy.deployService)
                                                           .map(m -> createSetSyncClient(m, redeploy.deployService))
                                                           .toStream()
                                                           .flatMap(Collection::stream)
                                                           .collect(Collectors.toCollection(ArrayList::new)));

                if (res == 0) {
                    return performedRedeployResult(redeploy);
                }

                return "Error performing deploy - find error log at %s"
                        .formatted("// TODO!!!");
            } catch (InterruptedException |
                     IOException e) {
                return "Error performing deploy: %s".formatted(e.getMessage());
            }
        });
    }

    private @NotNull List<ToolCallbackProvider> createSetSyncClient(McpSyncClient m, String deployService) {
        var computed = this.syncClients.compute(deployService, (key, prev) -> {
            if (prev == null) {
                return new DelegateMcpSyncClient(m);
            }

            prev.setClient(m);
            return prev;
        });

        return toToolCallbackProvider(m.listTools(), computed);
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DelegateMcpSyncClient {
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

    private List<ToolCallbackProvider> toToolCallbackProvider(McpSchema.ListToolsResult listToolsResult,
                                                              DelegateMcpSyncClient mcpSyncClient) {
        return listToolsResult.tools().stream()
                .flatMap(t -> {
                    try {
                        return Stream.<ToolCallbackProvider>of(new StaticToolCallbackProvider(
                                FunctionToolCallback
                                        .builder(t.name(), (i, o) -> {
                                            try {
                                                lock.readLock().lock();
                                                var tc = objectMapper.writeValueAsString(i);
                                                return mcpSyncClient.callTool(new McpSchema.CallToolRequest(t.name(), tc));
                                            } catch (JsonProcessingException e) {
                                                log.error("Error performing tool call {}", e.getMessage(), e);
                                                throw new RuntimeException(e);
                                            } finally {
                                                lock.readLock().unlock();
                                            }
                                        })
                                        .description(t.description())
                                        .inputSchema(getInputSchema(t))
                                        .build()));
                    } catch (JsonProcessingException e) {
                        log.error("Error resolving  tool callback provider for tools " + t.name(), e);
                        return Stream.empty();
                    }
                })
                .toList();
    }

    private String getInputSchema(McpSchema.Tool t) throws JsonProcessingException {
        return objectMapper.writeValueAsString(t.inputSchema());
    }

}
