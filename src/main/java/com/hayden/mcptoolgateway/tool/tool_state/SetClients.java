package com.hayden.mcptoolgateway.tool.tool_state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.mcptoolgateway.tool.PassthroughFunctionToolCallback;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.utilitymodule.concurrent.striped.StripedLock;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.result.Result;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.AuthResolver;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.tool.StaticToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;

import java.time.Instant;
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
    @Autowired
    DelegateMcpClientFactory delegateMcpClientFactory;

    final Map<String, DelegateMcpClient> syncClients = new ConcurrentHashMap<>();

    boolean clientHasError(String clientName) {
        return syncClients.containsKey(clientName) && StringUtils.isNotBlank(syncClients.get(clientName).error());
    }

    boolean hasClient(String clientName) {
        return syncClients.containsKey(clientName) && syncClients.get(clientName).client() != null;
    }

    boolean noMcpClient(String clientName) {
        return syncClients.containsKey(clientName) && syncClients.get(clientName).client() == null;
    }

    boolean noClientKey(String clientName) {
        return !syncClients.containsKey(clientName);
    }

    public boolean clientExistsNotInitialized(String service) {
        return hasClient(service)
                && !this.syncClients.get(service).isInitialized();
    }

    public boolean clientInitialized(String service) {
        return isMcpServerAvailable(service);
    }

    String getError(String clientName) {
        return clientHasError(clientName) ? syncClients.get(clientName).error() : "Client has no error.";
    }

    boolean isMcpServerAvailable(String key) {
        try {
            var isInit = Optional.ofNullable(this.syncClients.get(key))
                    .map(s -> s.isInitialized())
                    .orElse(false);
            return isInit;
        } catch (Exception e) {
            return false;
        }
    }

    @StripedLock
    public ToolDecoratorService.SetSyncClientResult setMcpClient(McpServerToolStates.DeployedService deployService,
                                                                 ToolDecoratorService.McpServerToolState mcpServerToolState,
                                                                 NamedClientMcpTransport transport) {
        return this.dynamicMcpToolCallbackProvider.buildClient(deployService.clientId(), transport)
                .map(m -> createSetSyncClient(m, deployService, mcpServerToolState))
                .onErrorFlatMapResult(err -> Result.ok(createSetClientErr(deployService, err, mcpServerToolState)))
                .unwrap();
    }

    @StripedLock
    public ToolDecoratorService.SetSyncClientResult setMcpClient(String deployService,
                                                                 ToolDecoratorService.McpServerToolState mcpServerToolState) {
        var d = new McpServerToolStates.DeployedService(deployService, AuthResolver.resolveUserOrDefault());
        return this.dynamicMcpToolCallbackProvider.buildClient(d.clientId())
                .map(m -> createSetSyncClient(m, d, mcpServerToolState))
                .onErrorFlatMapResult(err -> Result.ok(createSetClientErr(d, err, mcpServerToolState)))
                .unwrap();
    }

    @StripedLock
    public ToolDecoratorService.SetSyncClientResult setMcpClient(McpServerToolStates.DeployedService deployService,
                                                                 ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return this.dynamicMcpToolCallbackProvider.buildClient(deployService.clientId())
                .map(m -> createSetSyncClient(m, deployService, mcpServerToolState))
                .onErrorFlatMapResult(err -> Result.ok(createSetClientErr(deployService, err, mcpServerToolState)))
                .unwrap();
    }

    @StripedLock
    public <T> T killClientAndThen(ToolDecoratorService.McpServerToolState toolState,
                                   String clientName,
                                   Supplier<T> toDo) {
        if (CollectionUtils.isEmpty(toolState.added())) {
            return this.dynamicMcpToolCallbackProvider.killClientAndThen(clientName, toDo);
        } else {
            toolState.added()
                    .forEach(aClient -> {
                        var found = new McpServerToolStates.DeployedService(aClient.serverName(), aClient.userName());
                        this.dynamicMcpToolCallbackProvider.killClientAndThen(found.clientId(), () -> true);
                    });

            return this.dynamicMcpToolCallbackProvider.killClientAndThen(clientName, toDo);
        }
    }

    @StripedLock
    public ToolDecoratorService.SetSyncClientResult createSetClientErr(String service,
                                                                       DynamicMcpToolCallbackProvider.McpError m,
                                                                       ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return createSetClientErr(McpServerToolStates.getAuthDeployedService(service), m, mcpServerToolState);
    }

    @StripedLock
    public ToolDecoratorService.SetSyncClientResult createSetClientErr(McpServerToolStates.DeployedService service,
                                                                       DynamicMcpToolCallbackProvider.McpError m,
                                                                       ToolDecoratorService.McpServerToolState mcpServerToolState) {
        this.syncClients.compute(service.id(), (key, prev) -> {
            if (prev == null) {
                var created = delegateMcpClientFactory.clientFactory(mcpServerToolState);
                created.setError(m.getMessage());
                return created;
            }

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


    private @NotNull ToolDecoratorService.SetSyncClientResult createSetSyncClient(McpSyncClient m,
                                                                                  McpServerToolStates.DeployedService deployService,
                                                                                  ToolDecoratorService.McpServerToolState mcpServerToolState) {

        var mcpClient = this.syncClients.compute(deployService.deployService(), (key, prev) -> {
            if (prev == null) {
                return this.delegateMcpClientFactory.clientFactory(mcpServerToolState);
            }

            prev.setClient(m);
            return prev;
        });

        if (containsToolCallbackProviders(mcpServerToolState)) {
            return updateExisting(mcpClient, mcpServerToolState, deployService);
        } else {
            return createNew(mcpClient, deployService);
        }
    }

    private ToolDecoratorService.SetSyncClientResult createNew(DelegateMcpClient mcpClient,
                                                               McpServerToolStates.DeployedService deployService) {
        try {
            McpSchema.ListToolsResult listToolsResult = mcpClient.listTools();

            List<ToolCallbackProvider> providersCreated = new ArrayList<>();
            Set<String> toolsAdded = new HashSet<>();
            Set<String> toolsRemoved = new HashSet<>();
            Set<String> tools = new HashSet<>();
            Set<String> toolErrors = new HashSet<>();

            var t = toToolCallbackProvider(listToolsResult, mcpClient, deployService);

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
            return parseToolExceptionFail(e, mcpClient, deployService);
        }
    }

    private ToolDecoratorService.SetSyncClientResult updateExisting(DelegateMcpClient m,
                                                                    ToolDecoratorService.McpServerToolState removedState,
                                                                    McpServerToolStates.DeployedService deployService) {
        Map<String, ToolDecoratorService.ToolCallbackDescriptor> existing = toExistingToolCallbackProviders(removedState);

        try {
            McpSchema.ListToolsResult listToolsResult = m.listTools();

            List<ToolCallbackProvider> providersCreated = new ArrayList<>();
            Set<String>  toolsAdded = new HashSet<>();
            Set<String>  toolsRemoved = new HashSet<>();
            Set<String>  tools = new HashSet<>();
            Set<String> toolErrors = new HashSet<>();


            var newTools = listToolsResult.tools().stream()
                    .map(t -> Map.entry(getToolName(m.getClientInfo().name(), t.name()), t))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            log.info("Updating existing {}", newTools.keySet());

            for (var n : newTools.entrySet()) {
                var p = createToolCallbackProvider(m, n.getValue(), deployService);
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
            return parseToolExceptionFail(e, m, existing, deployService);
        }
    }

    private boolean containsToolCallbackProviders(ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return mcpServerToolState != null
                && !CollectionUtils.isEmpty(mcpServerToolState.toolCallbackProviders());
    }

    private List<ToolDecoratorService.CreateToolCallbackProviderResult> toToolCallbackProvider(McpSchema.ListToolsResult listToolsResult,
                                                                                               DelegateMcpClient mcpSyncClient,
                                                                                               McpServerToolStates.DeployedService deployService) {
        return listToolsResult.tools().stream()
                .map(t -> createToolCallbackProvider(mcpSyncClient, t, deployService))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private @NotNull ToolDecoratorService.CreateToolCallbackProviderResult createToolCallbackProvider(DelegateMcpClient mcpSyncClient,
                                                                                                      McpSchema.Tool t,
                                                                                                      McpServerToolStates.DeployedService deployedService) {
        try {
            var f = buildPassThroughToolCallback(mcpSyncClient, t, deployedService);

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

    private @NotNull PassthroughFunctionToolCallback buildPassThroughToolCallback(DelegateMcpClient mcpSyncClient,
                                                                                  McpSchema.Tool t,
                                                                                  McpServerToolStates.DeployedService deployedService) throws JsonProcessingException {
        String toolName = getToolName(mcpSyncClient.getClientInfo().name(), t.name());
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

    private static ToolDecoratorService.SetSyncClientResult parseToolExceptionFail(Exception e,
                                                                                   DelegateMcpClient mcpClient, McpServerToolStates.DeployedService deployService) {
        log.error("Error when attempting to retrieve tools: {}", e.getMessage(), e);
        mcpClient.setError(e.getMessage());
        return ToolDecoratorService.SetSyncClientResult.builder()
                .err(e.getMessage())
                .build();
    }

    private static ToolDecoratorService.SetSyncClientResult parseToolExceptionFail(Exception e,
                                                                                   DelegateMcpClient mcpClient,
                                                                                   Map<String, ToolDecoratorService.ToolCallbackDescriptor> existing,
                                                                                   McpServerToolStates.DeployedService deployService) {
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

    public interface DelegateMcpClient {


        McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest);


        McpSchema.ListToolsResult listTools();

        void setClient(McpSyncClient client);

        void setError(String error);

        McpSyncClient client();


        McpSchema.Implementation getClientInfo();


        boolean isInitialized();


        String error();
    }

    /**
     * Fan out to multiple servers.
     */
    @Data
    public static class MultipleClientDelegateMcpClient implements DelegateMcpClient {

        private final Map<String, SingleDelegateMcpClient> clients = new ConcurrentHashMap<>();

        @Override
        public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest) {
            var resolved = AuthResolver.resolveUser();
            return clients.get(resolved).callTool(callToolRequest);
        }

        @Override
        public McpSchema.ListToolsResult listTools() {
            var resolved = AuthResolver.resolveUser();
            return clients.get(resolved).listTools();
        }

        @Override
        public void setClient(McpSyncClient client) {
            var resolved = AuthResolver.resolveUser();
            this.clients.compute(resolved, (key, prev) -> {
                if (prev == null) {
                    prev = new SingleDelegateMcpClient();
                }

                prev.setClient(client);
                prev.setError(null);
                return prev;
            });
        }

        @Override
        public void setError(String error) {
            this.clients.compute(AuthResolver.resolveUser(), (key, prev) -> {
                if (prev == null) {
                    prev = new SingleDelegateMcpClient();
                }

                prev.setClient(null);
                prev.setError(error);
                return prev;
            });
        }

        @Override
        public McpSyncClient client() {
            var resolved = AuthResolver.resolveUser();
            return this.clients.get(resolved).client();
        }

        @Override
        public McpSchema.Implementation getClientInfo() {
            var resolved = AuthResolver.resolveUser();
            return this.clients.get(resolved).getClientInfo();
        }

        @Override
        public boolean isInitialized() {
            var resolved = AuthResolver.resolveUser();
            return this.clients.get(resolved).isInitialized();
        }

        @Override
        public String error() {
            var resolved = AuthResolver.resolveUser();
            return this.clients.get(resolved).error();
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SingleDelegateMcpClient implements DelegateMcpClient {

        private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

        volatile Instant lastAccessed;

        McpSyncClient client;

        boolean isStdio;

        /**
         * TODO: last error to return - or last error log file
         */
        String error;

        @Override
        public String error() {
            return error;
        }

        public McpSchema.Implementation getClientInfo() {
            lastAccessed = Instant.now();
            return client.getClientInfo();
        }

        public McpSchema.ClientCapabilities getClientCapabilities() {
            lastAccessed = Instant.now();
            return client.getClientCapabilities();
        }

        @Override
        public McpSchema.ListToolsResult listTools() {
            lastAccessed = Instant.now();
            return client.listTools();
        }

        @Override
        public boolean isInitialized() {
            lastAccessed = Instant.now();
            return client.isInitialized();
        }

        public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest) {
            try {
                if (isStdio) {
                    readWriteLock.writeLock().lock();
                } else {
                    readWriteLock.readLock().lock();
                }

                var resolved = AuthResolver.resolveBearerHeader();

                if (resolved != null) {
                    callToolRequest.arguments().put(ToolDecoratorService.AUTH_BODY_FIELD, resolved);
                }

                var called = client.callTool(callToolRequest);

                return called;
            } finally {
                lastAccessed = Instant.now();
                if (isStdio) {
                    readWriteLock.writeLock().unlock();
                } else {
                    readWriteLock.readLock().unlock();
                }
            }
        }

        public void setClient(McpSyncClient client) {
            try {
                this.readWriteLock.writeLock().lock();
                this.client = client;
                this.error = null;
                this.isStdio = client == null || isStdio(client);
            } finally {
                lastAccessed = Instant.now();
                this.readWriteLock.writeLock().unlock();
            }
        }

        @Override
        public void setError(String error) {
            try {
                this.readWriteLock.writeLock().lock();
                this.error = error;
                this.client = null;
            } finally {
                lastAccessed = Instant.now();
                this.readWriteLock.writeLock().unlock();
            }
        }

        @Override
        public McpSyncClient client() {
            lastAccessed = Instant.now();
            return client;
        }

        private boolean isStdio(McpSyncClient client) {
            try {
                var delegate = client.getClass().getDeclaredField("delegate");
                ReflectionUtils.makeAccessible(delegate);
                McpAsyncClient asyncClient = (McpAsyncClient) ReflectionUtils.getField(delegate, client);
                var clientTransportField = McpAsyncClient.class.getDeclaredField("transport");
                ReflectionUtils.makeAccessible(clientTransportField);
                var clientTransport = (McpClientTransport) ReflectionUtils.getField(clientTransportField, asyncClient);
                return clientTransport instanceof StdioClientTransport;
            } catch (NoSuchFieldException e) {
                log.error("Could not get delegate field.", e);
                return true;
            }
        }



    }
}
