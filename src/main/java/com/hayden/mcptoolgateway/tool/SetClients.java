package com.hayden.mcptoolgateway.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.utilitymodule.concurrent.striped.StripedLock;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.result.Result;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
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
import java.util.stream.Collectors;

@Component
@Slf4j
public class SetClients {


    /**
     * TODO: this should just contain the specifications in the return types, do it all in the other one.
     */
    @Autowired
    McpSyncServerDelegate mcpSyncServer;
    @Autowired
    DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;
    @Autowired
    ObjectMapper objectMapper;

    private final Map<String, ToolDecoratorService.DelegateMcpSyncClient> syncClients = new ConcurrentHashMap<>();

    boolean clientHasError(String clientName) {
        return syncClients.containsKey(clientName) && StringUtils.isNotBlank(syncClients.get(clientName).error);
    }

    boolean hasClient(String clientName) {
        return syncClients.containsKey(clientName) && syncClients.get(clientName).client != null;
    }

    boolean noMcpClient(String clientName) {
        return syncClients.containsKey(clientName) && syncClients.get(clientName).client == null;
    }

    boolean noClientKey(String clientName) {
        return !syncClients.containsKey(clientName);
    }

    boolean clientInitialized(String service) {
        return this.syncClients.containsKey(service)
                && this.syncClients.get(service).getClient() != null
                && this.syncClients.get(service).getClient().isInitialized();
    }

    String getError(String clientName) {
        return clientHasError(clientName) ? syncClients.get(clientName).error : "Client has no error.";
    }

    boolean isMcpServerAvailable(String key) {
        try {
            var isInit = this.syncClients.get(key).getClient().isInitialized();
            return isInit;
        } catch (Exception e) {
            return false;
        }
    }

    @StripedLock
    ToolDecoratorService.SetSyncClientResult setMcpClient(String deployService, ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return this.dynamicMcpToolCallbackProvider.buildClient(deployService)
                .map(m -> createSetSyncClient(m, deployService, mcpServerToolState))
                .onErrorFlatMapResult(err -> Result.ok(createSetClientErr(deployService, err, mcpServerToolState)))
                .unwrap();
    }

    @StripedLock
    ToolDecoratorService.SetSyncClientResult createSetClientErr(String service,
                                                                DynamicMcpToolCallbackProvider.McpError m,
                                                                ToolDecoratorService.McpServerToolState mcpServerToolState) {
        this.syncClients.compute(service, (key, prev) -> {
            if (prev == null) {
                return new ToolDecoratorService.DelegateMcpSyncClient(m.getMessage());
            }

            prev.setClient(null);
            prev.setError(m.getMessage());
            return prev;
        });

        Set<String> removed = new HashSet<>();
        Set<String> tools = new HashSet<>();

        if (containsToolCallbackProviders(mcpServerToolState)) {

            for (var tcp : mcpServerToolState.toolCallbackProviders()) {
                for (var tc : tcp.getToolCallbacks()) {
                    mcpSyncServer.removeTool(tc.getToolDefinition().name());
                    removed.add(tc.getToolDefinition().name());
                }
            }

            return ToolDecoratorService.SetSyncClientResult.builder()
                    .lastDeploy(mcpServerToolState.lastDeploy())
                    .toolsRemoved(removed)
                    .tools(tools)
                    .err(m.getMessage())
                    .build();
        }

        return ToolDecoratorService.SetSyncClientResult.builder()
                .tools(tools)
                .err(m.getMessage())
                .build();
    }


    private @NotNull ToolDecoratorService.SetSyncClientResult createSetSyncClient(McpSyncClient m, String deployService, ToolDecoratorService.McpServerToolState mcpServerToolState) {

        var mcpClient = this.syncClients.compute(deployService, (key, prev) -> {
            if (prev == null) {
                return new ToolDecoratorService.DelegateMcpSyncClient(m);
            }

            prev.setClient(m);
            prev.setError(null);
            return prev;
        });

        if (containsToolCallbackProviders(mcpServerToolState)) {
            return updateExisting(mcpClient, mcpServerToolState);
        } else {
            return createNew(mcpClient);
        }
    }

    private ToolDecoratorService.SetSyncClientResult createNew(ToolDecoratorService.DelegateMcpSyncClient mcpClient) {
        try {
            McpSchema.ListToolsResult listToolsResult = mcpClient.client.listTools();

            List<ToolCallbackProvider> providersCreated = new ArrayList<>();
            Set<String> toolsAdded = new HashSet<>();
            Set<String> toolsRemoved = new HashSet<>();
            Set<String> tools = new HashSet<>();
            Set<String> toolErrors = new HashSet<>();

            var t = toToolCallbackProvider(listToolsResult, mcpClient);
            for (var tcp : t) {
                if (tcp.provider() == null) {
//                  there is no existing!
                    addToErr(tcp, toolErrors);
                } else {
                    Arrays.stream(tcp.provider().getToolCallbacks())
                            .forEach(tc -> mcpSyncServer.addTool(McpToolUtils.toSyncToolSpecification(tc)));
                    toolsAdded.add(tcp.toolName().name());
                    tools.add(tcp.toolName().name());
                    providersCreated.add(tcp.provider());
                }
            }

            return ToolDecoratorService.SetSyncClientResult.builder()
                    .toolsAdded(toolsAdded)
                    .toolsRemoved(toolsRemoved)
                    .tools(tools)
                    .providers(providersCreated)
                    .build();
        } catch (Exception e) {
            return parseToolExceptionFail(e, mcpClient);
        }
    }

    private ToolDecoratorService.SetSyncClientResult updateExisting(ToolDecoratorService.DelegateMcpSyncClient m, ToolDecoratorService.McpServerToolState removedState) {
        Map<String, ToolDecoratorService.ToolCallbackDescriptor> existing = toExistingToolCallbackProviders(removedState);

        try {
            McpSchema.ListToolsResult listToolsResult = m.client.listTools();

            List<ToolCallbackProvider> providersCreated = new ArrayList<>();
            Set<String>  toolsAdded = new HashSet<>();
            Set<String>  toolsRemoved = new HashSet<>();
            Set<String>  tools = new HashSet<>();
            Set<String> toolErrors = new HashSet<>();


            var newTools = listToolsResult.tools().stream()
                    .map(t -> Map.entry("%s.%s".formatted(m.client.getClientInfo().name(), t.name()), t))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            for (var n : newTools.entrySet()) {
                var p = createToolCallbackProvider(m, n.getValue());
                Optional.ofNullable(p.provider())
                        .ifPresentOrElse(tcp -> {
                            toolsAdded.add(p.toolName().name());
                            tools.add(p.toolName().name());
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

            return ToolDecoratorService.SetSyncClientResult.builder()
                    .toolsAdded(toolsAdded)
                    .toolsRemoved(toolsRemoved)
                    .tools(tools)
                    .lastDeploy(removedState.lastDeploy())
                    .providers(providersCreated)
                    .build();
        } catch (Exception e) {
            return parseToolExceptionFail(e, m, existing);
        }
    }

    private boolean containsToolCallbackProviders(ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return mcpServerToolState != null
                && !CollectionUtils.isEmpty(mcpServerToolState.toolCallbackProviders());
    }

    private List<ToolDecoratorService.CreateToolCallbackProviderResult> toToolCallbackProvider(McpSchema.ListToolsResult listToolsResult,
                                                                                               ToolDecoratorService.DelegateMcpSyncClient mcpSyncClient) {
        return listToolsResult.tools().stream()
                .map(t -> createToolCallbackProvider(mcpSyncClient, t))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private @NotNull ToolDecoratorService.CreateToolCallbackProviderResult createToolCallbackProvider(ToolDecoratorService.DelegateMcpSyncClient mcpSyncClient,
                                                                                                      McpSchema.Tool t) {
        try {
            return ToolDecoratorService.CreateToolCallbackProviderResult.builder()
                    .toolName(t)
                    .provider(new StaticToolCallbackProvider(
                            FunctionToolCallback
                                    .builder("%s.%s".formatted(mcpSyncClient.client.getClientInfo().name().replace("replace-name - ", ""), t.name()), (i, o) -> {
                                        try {
                                            var tc = objectMapper.writeValueAsString(i);
                                            return mcpSyncClient.callTool(new McpSchema.CallToolRequest(t.name(), tc));
                                        } catch (Exception e) {
                                            log.error("Error performing tool call {}", e.getMessage(), e);
                                            return "Could not process tool call result %s from tool %s with err %s."
                                                    .formatted(String.valueOf(i), t.name(), e.getMessage());
                                        }
                                    })
                                    .description(t.description())
                                    .inputSchema(getInputSchema(t))
                                    .inputType(String.class)
                                    .build()))
                    .build();
        } catch (JsonProcessingException e) {
            log.error("Error resolving  tool callback provider for tools {}", t.name(), e);
            return ToolDecoratorService.CreateToolCallbackProviderResult.builder()
                    .e(e)
                    .build();
        }
    }

    private static void addToErr(ToolDecoratorService.CreateToolCallbackProviderResult p, Set<String> toolErrors) {
        if (p.e() == null) {
            toolErrors.add("Unknown error creating tool callback for %s".formatted(p.toolName().name()));
        } else {
            toolErrors.add("Error creating tool callback for %s: %s".formatted(p.toolName().name(), p.e().getMessage()));
        }
    }


    private String getInputSchema(McpSchema.Tool t) throws JsonProcessingException {
        return objectMapper.writeValueAsString(t.inputSchema());
    }

    private static ToolDecoratorService.SetSyncClientResult parseToolExceptionFail(Exception e, ToolDecoratorService.DelegateMcpSyncClient mcpClient) {
        log.error("Error when attempting to retrieve tools: {}", e.getMessage(), e);
        mcpClient.setError(e.getMessage());
        return ToolDecoratorService.SetSyncClientResult.builder()
                .err(e.getMessage())
                .build();
    }

    private static ToolDecoratorService.SetSyncClientResult parseToolExceptionFail(Exception e, ToolDecoratorService.DelegateMcpSyncClient mcpClient, Map<String, ToolDecoratorService.ToolCallbackDescriptor> existing) {
        log.error("Error when attempting to retrieve tools: {}", e.getMessage(), e);
        mcpClient.setError(e.getMessage());
        return ToolDecoratorService.SetSyncClientResult.builder()
                .toolsRemoved(existing.keySet())
                .err(e.getMessage())
                .build();
    }

    private static @NotNull HashMap<String, ToolDecoratorService.ToolCallbackDescriptor> toExistingToolCallbackProviders(ToolDecoratorService.McpServerToolState removedState) {
        if (removedState == null)
            return new HashMap<>();
        List<ToolCallbackProvider> removedProviders = Optional.ofNullable(removedState.toolCallbackProviders()).orElse(new ArrayList<>());

        var existing = new HashMap<String, ToolDecoratorService.ToolCallbackDescriptor>();

        for (int i =0; i < removedProviders.size(); i++) {
            var provider = removedProviders.get(i);
            for (ToolCallback toolCallback : provider.getToolCallbacks()) {
                existing.put(toolCallback.getToolDefinition().name(), new ToolDecoratorService.ToolCallbackDescriptor(provider, toolCallback));
            }
        }
        return existing;
    }

}
