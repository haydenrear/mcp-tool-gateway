package com.hayden.mcptoolgateway.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.fn.RedeployFunction;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.result.Result;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.*;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.quota.ClientQuotaAlteration;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.StaticToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

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
    @Autowired
    RedeployFunction redeployFunction;

    @Getter
    Map<String, McpServerToolState> toolCallbackProviders;

    @Autowired
    McpSyncServer mcpSyncServer;

    @Builder(toBuilder = true)
    public record McpServerToolState(List<ToolCallbackProvider> toolCallbackProviders,
                                     RedeployFunction.RedeployDescriptor lastDeploy) {}

    private final ConcurrentHashMap<String, DelegateMcpSyncClient> syncClients = new ConcurrentHashMap<>();

    private final ReentrantReadWriteLock  lock = new ReentrantReadWriteLock();

    @PostConstruct
    public void init() {
        buildTools();
    }

    private void buildTools() {
        Map<String, McpServerToolState> decoratedTools = toolGatewayConfigProperties
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
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toCollection(ArrayList::new))))
                .entrySet()
                .stream()
                .map(e -> Map.entry(e.getKey(), McpServerToolState.builder().toolCallbackProviders(e.getValue()).build()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // TODO: add redeploy tools - easy to add.
        this.toolCallbackProviders = addRedeployTools(decoratedTools);
    }

    public record Redeploy(@JsonProperty("Service to redeploy") String deployService) {}

    private Map<String, McpServerToolState> addRedeployTools(Map<String, McpServerToolState> toolCallbackProviders) {
        toolCallbackProviders.put("redeploy-mcp-server", McpServerToolState.builder().toolCallbackProviders(Lists.newArrayList(
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
                                        .build())))
                .build());

        return toolCallbackProviders;
    }

    private static @NotNull String performedRedeployResult(Redeploy i) {
        return "performed redeploy of %s.".formatted(i.deployService);
    }

    public record RedeployResult(
            List<String> toolsRemoved,
            List<String> toolsAdded,
            List<String> tools,
            String deployErr,
            String mcpConnectErr
    ) {}

    public RedeployResult doRedeploy(Redeploy redeploy, ToolGatewayConfigProperties.DeployableMcpServer redeployMcpServer) {
        return this.dynamicMcpToolCallbackProvider.killClientAndThen(redeploy.deployService, () -> {
            try {
                lock.writeLock().lock();
                var r = redeployFunction.performRedeploy(redeployMcpServer);

                if (!r.isSuccess()) {
                    log.debug("Failed to perform redeploy {}.", r);
                    var tc = createSetClientErr(new DynamicMcpToolCallbackProvider.McpError(""), redeploy);
                    this.toolCallbackProviders.put(
                            redeploy.deployService,
                            McpServerToolState.builder()
                                    .toolCallbackProviders(tc)
                                    .lastDeploy(r)
                                    .build());
                    return performedRedeployResult(redeploy);
                }

                McpServerToolState built = McpServerToolState.builder()
                        .toolCallbackProviders(
                                this.dynamicMcpToolCallbackProvider.buildClient(redeploy.deployService)
                                        .map(m -> createSetSyncClient(m, redeploy.deployService))
                                        .onErrorFlatMapResult(err -> Result.ok(createSetClientErr(err, redeploy)))
                                        .toStream()
                                        .flatMap(Collection::stream)
                                        .collect(Collectors.toCollection(ArrayList::new)))
                        .build();

                this.toolCallbackProviders.put(redeploy.deployService, built);

                return handleConnectMcpError(redeploy, r);
            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    private @NotNull RedeployResult handleConnectMcpError(Redeploy redeploy, RedeployFunction.RedeployDescriptor r) {
        if (!this.syncClients.containsKey(redeploy.deployService)) {
            return "MCP client was not found for %s"
                    .formatted(redeploy.deployService);
        } else if (this.syncClients.get(redeploy.deployService).getClient() == null) {
            var err = this.syncClients.get(redeploy.deployService).getError();
            if (err != null) {
                return "Error connecting to MCP client for %s after redeploy: %s"
                        .formatted(redeploy, err);
            } else {
                return "Unknown connecting to MCP client for %s after redeploy"
                        .formatted(redeploy);
            }
        } else {
            return "Performed redeploy for %s: %s.".formatted(redeploy.deployService, r);
        }
    }

    @Builder(toBuilder = true)
    public record SetSyncClientResult(List<String> tools,
                                      List<String> toolsAdded,
                                      List<String> toolsRemoved,
                                      String err,
                                      List<ToolCallbackProvider> providers) {}

    private @NotNull SetSyncClientResult createSetClientErr(DynamicMcpToolCallbackProvider.McpError m,
                                                                   Redeploy deployService) {
        this.syncClients.compute(deployService.deployService, (key, prev) -> {
            if (prev == null) {
                return new DelegateMcpSyncClient(m.getMessage());
            }

            prev.setClient(null);
            prev.setError(m.getMessage());
            return prev;
        });

        if (containsToolCallbackProviders(deployService.deployService)) {
            return this.toolCallbackProviders.get(deployService.deployService).toolCallbackProviders();
        }

        return new ArrayList<>();
    }


    private @NotNull SetSyncClientResult createSetSyncClient(McpSyncClient m, String deployService) {
        var computed = this.syncClients.compute(deployService, (key, prev) -> {
            if (prev == null) {
                return new DelegateMcpSyncClient(m);
            }

            prev.setClient(m);
            return prev;
        });

        if (containsToolCallbackProviders(deployService)) {
            List<ToolCallbackProvider> providers = this.toolCallbackProviders.get(deployService)
                    .toolCallbackProviders();
            
            var existing = providers.stream()
                    .flatMap(tc -> Arrays.stream(tc.getToolCallbacks()))
                    .map(tc -> Map.entry(tc.getToolDefinition().name(), tc))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            var newTools = m.listTools().tools().stream()
                    .map(t -> Map.entry(t.name(), t))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            
            for (var n : newTools.entrySet()) {
                if (!existing.containsKey(n.getKey())) {
                    createToolCallbackProvider(computed, deployService, n.getValue())
                            .ifPresentOrElse(tcp -> {
                                providers.add(tcp);
                                Arrays.stream(tcp.getToolCallbacks())
                                                .forEach(tc -> mcpSyncServer.addTool(McpToolUtils.toSyncToolSpecification(tc)));
                            }, () -> log.error("Unable to create tool callback provider for {}.", n.getKey()));
                }
            }
            
            for (var n : existing.entrySet()) {
                if (!newTools.containsKey(n.getKey())) {
                    log.info("Removing tool {}", n.getKey());
                    mcpSyncServer.removeTool(n.getKey());
                    int i = 0;
                    for (var p : providers) {
                        for (var t : p.getToolCallbacks()) {
                            if (t.getToolDefinition().name().equals(n.getKey())) {
                                break;
                            }
                        }

                        i += 1;
                    }

                    // each tool callback provider only has one tool.
                    providers.remove(i);
                }
            }
            
            return providers;
        }

        var t = toToolCallbackProvider(m.listTools(), computed, deployService);

        for (var tcp : t) {
            Arrays.stream(tcp.getToolCallbacks())
                    .forEach(tc -> mcpSyncServer.addTool(McpToolUtils.toSyncToolSpecification(tc)));
        }

        return t;
    }

    private boolean containsToolCallbackProviders(String deployService) {
        return this.toolCallbackProviders.containsKey(deployService)
                && !CollectionUtils.isEmpty(this.toolCallbackProviders.get(deployService).toolCallbackProviders);
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
                                                              DelegateMcpSyncClient mcpSyncClient,
                                                              String service) {
        return listToolsResult.tools().stream()
                .flatMap(t -> createToolCallbackProvider(mcpSyncClient, service, t).stream())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private @NotNull Optional<ToolCallbackProvider> createToolCallbackProvider(DelegateMcpSyncClient mcpSyncClient, String service, McpSchema.Tool t) {
        try {
            return Optional.of(new StaticToolCallbackProvider(
                    FunctionToolCallback
                            .builder(t.name(), (i, o) -> {
                                try {
                                    lock.readLock().lock();
                                    if (mcpSyncClient.client == null) {
                                        var last = this.toolCallbackProviders.get(service).lastDeploy;
                                        return "Attempted to call tool %s with MCP client err %s and deploy err %s."
                                                .formatted(t.name(), mcpSyncClient.error, last);
                                    }
                                    var tc = objectMapper.writeValueAsString(i);
                                    return mcpSyncClient.callTool(new McpSchema.CallToolRequest(t.name(), tc));
                                } catch (
                                        JsonProcessingException e) {
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
            return Optional.empty();
        }
    }

    private String getInputSchema(McpSchema.Tool t) throws JsonProcessingException {
        return objectMapper.writeValueAsString(t.inputSchema());
    }

}
