package com.hayden.mcptoolgateway.tool.tool_state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.mcptoolgateway.tool.PassthroughFunctionToolCallback;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.utilitymodule.concurrent.striped.StripedLock;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.result.Result;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.AuthResolver;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.StaticToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
@Slf4j
class SetClients {

    /**
     * TODO: this should just contain the specifications in the return types, do it all in the other one.
     */
    @Autowired
    McpSyncServerDelegate mcpSyncServerDelegate;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;

    final Map<String, DelegateMcpSyncClient> syncClients = new ConcurrentHashMap<>();

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

    public boolean clientExistsNotInitialized(String service) {
        return hasClient(service)
                && !this.syncClients.get(service).getClient().isInitialized();
    }

    public boolean clientInitialized(String service) {
        return isMcpServerAvailable(service);
    }

    String getError(String clientName) {
        return clientHasError(clientName) ? syncClients.get(clientName).error : "Client has no error.";
    }

    boolean isMcpServerAvailable(String key) {
        try {
            var isInit = Optional.ofNullable(this.syncClients.get(key))
                    .flatMap(d -> Optional.ofNullable(d.getClient()))
                    .map(McpSyncClient::isInitialized)
                    .orElse(false);
            return isInit;
        } catch (Exception e) {
            return false;
        }
    }

    @StripedLock
    public ToolDecoratorService.SetSyncClientResult setMcpClient(String deployService, ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return this.dynamicMcpToolCallbackProvider.buildClient(deployService)
                .map(m -> createSetSyncClient(m, deployService, mcpServerToolState))
                .onErrorFlatMapResult(err -> Result.ok(createSetClientErr(deployService, err, mcpServerToolState)))
                .unwrap();
    }

    @StripedLock
    public <T> T killClientAndThen(String clientName, Supplier<T> toDo) {
        return this.dynamicMcpToolCallbackProvider.killClientAndThen(clientName, toDo);
    }

    @StripedLock
    public ToolDecoratorService.SetSyncClientResult createSetClientErr(String service,
                                                                       DynamicMcpToolCallbackProvider.McpError m,
                                                                       ToolDecoratorService.McpServerToolState mcpServerToolState) {
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

        if (containsToolCallbackProviders(mcpServerToolState)) {

            for (var tcp : mcpServerToolState.toolCallbackProviders()) {
                for (var tc : tcp.getToolCallbacks()) {
                    mcpSyncServerDelegate.removeTool(tc.getToolDefinition().name());
                    removed.add(tc.getToolDefinition().name());
                }
            }

            return ToolDecoratorService.SetSyncClientResult.builder()
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
                return new DelegateMcpSyncClient(m);
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

    private ToolDecoratorService.SetSyncClientResult createNew(DelegateMcpSyncClient mcpClient) {
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
                            .forEach(tc -> {
                                mcpSyncServerDelegate.addTool(McpToolUtils.toSyncToolSpecification(tc));
                                toolsAdded.add(tc.getToolDefinition().name());
                                tools.add(tc.getToolDefinition().name());
                            });
                    log.info("Adding new toolfor callback {}", tcp.toolName().name());
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

    private ToolDecoratorService.SetSyncClientResult updateExisting(DelegateMcpSyncClient m, ToolDecoratorService.McpServerToolState removedState) {
        Map<String, ToolDecoratorService.ToolCallbackDescriptor> existing = toExistingToolCallbackProviders(removedState);

        try {
            McpSchema.ListToolsResult listToolsResult = m.client.listTools();

            List<ToolCallbackProvider> providersCreated = new ArrayList<>();
            Set<String>  toolsAdded = new HashSet<>();
            Set<String>  toolsRemoved = new HashSet<>();
            Set<String>  tools = new HashSet<>();
            Set<String> toolErrors = new HashSet<>();


            var newTools = listToolsResult.tools().stream()
                    .map(t -> Map.entry(getToolName(m.client.getClientInfo().name(), t.name()), t))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            log.info("Updating existing {}", newTools.keySet());

            for (var n : newTools.entrySet()) {
                var p = createToolCallbackProvider(m, n.getValue());
                Optional.ofNullable(p.provider())
                        .ifPresentOrElse(tcp -> {
                            if (!existing.containsKey(n.getKey())) {
                                log.info("Adding new {}", n.getKey());
                                toolsAdded.add(n.getKey());
                            } else {
                                mcpSyncServerDelegate.removeTool(n.getKey());
                            }
                            tools.add(n.getKey());
                            providersCreated.add(tcp);
                            Arrays.stream(tcp.getToolCallbacks())
                                    .forEach(tc -> mcpSyncServerDelegate.addTool(McpToolUtils.toSyncToolSpecification(tc)));
                        }, () -> {
                            log.error("Unable to create tool callback provider for {}.", n.getKey());
                            addToErr(p, toolErrors);
                        });
            }

            for (var n : existing.entrySet()) {
                if (!newTools.containsKey(n.getKey())) {
                    log.info("Removing tool {} from {}", n.getKey(), newTools.keySet());
                    mcpSyncServerDelegate.removeTool(n.getKey());
                    toolsRemoved.add(n.getKey());
                }
            }

            return ToolDecoratorService.SetSyncClientResult.builder()
                    .toolsAdded(toolsAdded)
                    .toolsRemoved(toolsRemoved)
                    .tools(tools)
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
                                                                                               DelegateMcpSyncClient mcpSyncClient) {
        return listToolsResult.tools().stream()
                .map(t -> createToolCallbackProvider(mcpSyncClient, t))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private @NotNull ToolDecoratorService.CreateToolCallbackProviderResult createToolCallbackProvider(DelegateMcpSyncClient mcpSyncClient,
                                                                                                      McpSchema.Tool t) {
        try {
            var f = buildPassThroughToolCallback(mcpSyncClient, t);

            return ToolDecoratorService.CreateToolCallbackProviderResult.builder()
                    .toolName(t)
                    .provider(new StaticToolCallbackProvider(f))
                    .build();
        } catch (JsonProcessingException e) {
            log.error("Error resolving  tool callback provider for tools {}", t.name(), e);
            return ToolDecoratorService.CreateToolCallbackProviderResult.builder()
                    .e(e)
                    .build();
        }
    }

    private @NotNull PassthroughFunctionToolCallback buildPassThroughToolCallback(DelegateMcpSyncClient mcpSyncClient, McpSchema.Tool t) throws JsonProcessingException {
        String toolName = getToolName(mcpSyncClient.client.getClientInfo().name(), t.name());
        var toolDefinition = DefaultToolDefinition.builder()
                .name(toolName)
                .description(t.description())
                .inputSchema(objectMapper.writeValueAsString(t.inputSchema()))
                .build();

        BiFunction<String, ToolContext, String> toolFunction = (i, o) -> {
            log.info("Running tool callback for {}.", toolName);
            try {
                McpSchema.CallToolResult value = mcpSyncClient.callTool(new McpSchema.CallToolRequest(t.name(), i));
                if (value.isError() && CollectionUtils.isEmpty(value.content())) {
                    return """
                            { "error": "true", "message": "Unknown error." }
                            """;
                } else {
                    if (value.content().size() == 1) {
                        return parseResponseItem(value.content().getFirst());
                    } else {
                        log.error("Found content size greater than 1. Not knowing what to do. Only returning first.");
                        return parseResponseItem(value.content().getFirst());
                    }
                }
            } catch (Exception e) {
                log.error("Error performing tool call {}", e.getMessage(), e);
                return "Could not process tool call result %s from tool %s with err %s."
                        .formatted(String.valueOf(i), t.name(), e.getMessage());
            }
        };
        var f = new PassthroughFunctionToolCallback(toolFunction, toolDefinition);
        return f;
    }

    private static String parseResponseItem(McpSchema.Content first) {
        return switch(first) {
            case McpSchema.EmbeddedResource embeddedResource -> {
                log.error("Found unknown embedded resource");
                throw new RuntimeException("Unimplemented");
            }
            case McpSchema.ImageContent imageContent -> {
                log.error("Found unknown image resource");
                throw new RuntimeException("Unimplemented");
            }
            case McpSchema.TextContent(List<McpSchema.Role> audience, Double priority, String text) -> {
                yield text;
            }
        };
    }

    private static @NotNull String getToolName(String serviceName, String toolName) {
        log.info("Getting tool name for service {}, tool name {}", serviceName, toolName);
        String formatted = "%s-%s".formatted(doReplaceName(serviceName), toolName);
        return formatted;
    }

    private static @NotNull String doReplaceName(String serviceName) {
        return serviceName.replace("replace-name - ", "");
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

    private static ToolDecoratorService.SetSyncClientResult parseToolExceptionFail(Exception e, DelegateMcpSyncClient mcpClient) {
        log.error("Error when attempting to retrieve tools: {}", e.getMessage(), e);
        mcpClient.setError(e.getMessage());
        return ToolDecoratorService.SetSyncClientResult.builder()
                .err(e.getMessage())
                .build();
    }

    private static ToolDecoratorService.SetSyncClientResult parseToolExceptionFail(Exception e, DelegateMcpSyncClient mcpClient, Map<String, ToolDecoratorService.ToolCallbackDescriptor> existing) {
        log.error("Error when attempting to retrieve tools: {}", e.getMessage(), e);
        mcpClient.setError(e.getMessage());
        return ToolDecoratorService.SetSyncClientResult.builder()
                .toolsRemoved(existing.keySet())
                .err(e.getMessage())
                .build();
    }

    private static @NotNull HashMap<String, ToolDecoratorService.ToolCallbackDescriptor> toExistingToolCallbackProviders(ToolDecoratorService.McpServerToolState removedState) {
        if (removedState == null) {
            log.info("Removed state was null.");
            return new HashMap<>();
        }

        List<ToolCallbackProvider> removedProviders = Optional.ofNullable(removedState.toolCallbackProviders()).orElse(new ArrayList<>());

        var existing = new HashMap<String, ToolDecoratorService.ToolCallbackDescriptor>();

        for (int i =0; i < removedProviders.size(); i++) {
            var provider = removedProviders.get(i);
            for (ToolCallback toolCallback : provider.getToolCallbacks()) {
                log.info("Found removed {}", toolCallback.getToolDefinition().name());
                existing.put(toolCallback.getToolDefinition().name(), new ToolDecoratorService.ToolCallbackDescriptor(provider, toolCallback));
            }
        }
        return existing;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DelegateMcpSyncClient {
        private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock() ;

        McpSyncClient client;

        /**
         * TODO: last error to return - or last error log file
         */
        String error;

        public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest) {
            try {
                readWriteLock.readLock().lock();
                var resolved = AuthResolver.resolveBearerHeader();
                if (resolved != null)
                    callToolRequest.arguments().put(ToolDecoratorService.McpServerToolState.AUTH_BODY_FIELD, resolved);
                var called = client.callTool(callToolRequest);
                return called;
            } finally {
                readWriteLock.readLock().unlock();
            }
        }

        public void setClient(McpSyncClient client) {
            try {
                this.readWriteLock.writeLock().lock();
                this.client = client;
            } finally {
                this.readWriteLock.writeLock().unlock();
            }
        }

        public void setError(String error) {
            try {
                this.readWriteLock.writeLock().lock();
                this.error = error;
            } finally {
                this.readWriteLock.writeLock().unlock();
            }
        }

        public DelegateMcpSyncClient(McpSyncClient client) {
            this.client = client;
        }

        public DelegateMcpSyncClient(String error) {
            this.error = error;
        }

    }
}
