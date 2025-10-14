package com.hayden.mcptoolgateway.tool.tool_state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.mcptoolgateway.tool.PassthroughFunctionToolCallback;
import com.hayden.mcptoolgateway.tool.ToolDecorator;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.utilitymodule.concurrent.striped.StripedLock;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.free.Free;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.McpSyncClient;
import com.hayden.mcptoolgateway.security.AuthResolver;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
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
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class McpServerToolStates {

    final SetClients setClients;

    private final McpSyncServerDelegate syncServerDelegate;

    private final AuthResolver authResolver;

    private final ObjectMapper objectMapper;

    private final Map<String, ToolDecoratorService.McpServerToolState> mcpServerToolStates = new ConcurrentHashMap<>();

    private volatile boolean didInitialize = false;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public Map<String, ToolDecoratorService.McpServerToolState> copyOf() {
        return new HashMap<>(mcpServerToolStates);
    }

     public void doOverWriteState(Runnable toDo) {
        try {
            lock.writeLock().lock();
            toDo.run();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void doPerformInitialization(Supplier<Boolean> toDo) {
        try {
            lock.writeLock().lock();
            if (toDo.get())
                initialized();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public <U> U doOverReadState(Supplier<U> toDo) {
        try {
            lock.readLock().lock();
            return toDo.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    public <U> U doOverWriteState(Supplier<U> toDo) {
        try {
            lock.writeLock().lock();
            return toDo.get();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void initialized() {
        didInitialize = true;
    }

    @StripedLock
    public <T> T killClientAndThen(ToolDecoratorService.McpServerToolState toolState, String clientName, Supplier<T> toDo) {
        return setClients.killClientAndThen(toolState, clientName, toDo);
    }

    public Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult> killClientAndThenFree(
            ToolDecoratorService.McpServerToolState toolState,
            String clientName,
            Supplier<Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult>> toDo) {
        return setClients.killClientAndThenFree(toolState, clientName, toDo);
    }

    @StripedLock
    public Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult> setMcpClientUpdateToolState(
            String deployService,
            ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return setClients.setMcpClientUpdateToolState(deployService, mcpServerToolState);
    }

    @StripedLock
    public ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult setParseMcpClient(String deployService, ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return setClients.setParseMcpClientUpdateToolState(deployService, mcpServerToolState);
    }

    @StripedLock
    public Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult> setMcpClient(DeployedService deployService,
                                                                                                                         ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return setClients.setMcpClient(deployService, mcpServerToolState);
    }

    @StripedLock
    public Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult> setMcpClient(DeployedService deployService,
                                                                                                                         ToolDecoratorService.McpServerToolState mcpServerToolState,
                                                                                                                         NamedClientMcpTransport namedTransport) {
        return setClients.setMcpClient(deployService, mcpServerToolState, namedTransport);
    }

    @StripedLock
    public ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult createSetClientErr(String service, DynamicMcpToolCallbackProvider.McpError m,
                                                                                               ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return createSetClientErr(setClients.getAuthDeployedService(service), m, mcpServerToolState);
    }

    @StripedLock
    public ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult createSetClientErr(DeployedService service, DynamicMcpToolCallbackProvider.McpError m, ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return setClients.createSetClientErr(service, m, mcpServerToolState);
    }

    public Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult> setMcpClientUpdateTools(
            McpSyncClient m,
            McpServerToolStates.DeployedService deployService,
            ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return setClients.setMcpClientUpdateTools(m, deployService, mcpServerToolState, this.doSetMcpClient(m, deployService, mcpServerToolState));
    }

    public void addUpdateToolState(String name, ToolDecoratorService.McpServerToolState mcpServerToolState) {
        this.mcpServerToolStates.put(name, mcpServerToolState);
    }

    public void addUpdateToolState(Map<String, ToolDecoratorService.McpServerToolState> states) {
        this.mcpServerToolStates.putAll(states);
    }

    public void addAllUpdates(Map<String, ToolDecoratorService.McpServerToolState> states) {
        this.mcpServerToolStates.putAll(states);
    }

    public void addUpdateToolState(ToolDecorator.ToolDecoratorToolStateUpdate toolDecoratorToolStateUpdate) {
        switch(toolDecoratorToolStateUpdate) {
            case ToolDecorator.ToolDecoratorToolStateUpdate.AddToolStateUpdate addToolStateUpdate -> {
                this.addUpdateToolState(addToolStateUpdate.name(), toolDecoratorToolStateUpdate.toolStates());
            }
        }
    }

    public void executeToolStateChanges(List<ToolDecorator.McpServerToolStateChange> mcpServerToolStateChanges) {
        mcpServerToolStateChanges
                .forEach(m -> {
                    switch(m) {
                        case ToolDecorator.McpServerToolStateChange.AddTool addTool ->
                                this.syncServerDelegate.addTool(addTool.toAddTools());
                        case ToolDecorator.McpServerToolStateChange.RemoveTool removeTool ->
                                this.syncServerDelegate.removeTool(removeTool.toRemove());
                    }
                });
    }

    public boolean isInitialized() {
        return didInitialize;
    }

    public ToolDecoratorService.McpServerToolState removeToolState(String s) {
        return this.mcpServerToolStates.remove(s);
    }

    public boolean clientHasError(String clientName) {
        return setClients.clientHasError(clientName);
    }

    public boolean hasClient(String clientName) {
        return setClients.hasClient(clientName);
    }

    public boolean noMcpClient(String clientName) {
        return setClients.noMcpClient(clientName);
    }

    public boolean noClientKey(String clientName) {
        return setClients.noClientKey(clientName);
    }

    public boolean clientExistsNotInitialized(String service) {
        return setClients.clientExistsNotInitialized(service);
    }

    public boolean clientInitialized(String service) {
        return setClients.clientInitialized(service);
    }

    public String getError(String clientName) {
        return setClients.getError(clientName);
    }

    public boolean isMcpServerAvailable(String key) {
        return setClients.isMcpServerAvailable(key);
    }

    @Autowired
    public void setMcpSyncServer(McpSyncServer mcpSyncServer) {
        syncServerDelegate.setMcpSyncServer(mcpSyncServer);
    }

    public void addTool(McpServerFeatures.SyncToolSpecification toolHandler) {
        syncServerDelegate.addTool(toolHandler);
    }

    public void removeTool(String toolName) {
        syncServerDelegate.removeTool(toolName);
    }

    public void notifyToolsListChanged() {
        syncServerDelegate.notifyToolsListChanged();
    }

    public boolean contains(String s) {
        return this.mcpServerToolStates.containsKey(s);
    }

    public void addClient(ToolDecoratorService.AddClient serverName) {
        this.mcpServerToolStates.compute(serverName.serverName(), (key, prev) -> {
            if (prev != null)
                prev.added().add(serverName);

            return prev;
        });
    }

    public record DeployedService(String deployService, String id) {
        public String clientId() {
            if (Objects.equals(id, ToolDecoratorService.SYSTEM_ID)) {
                return deployService;
            } else {
                Assert.isTrue(StringUtils.isNotBlank(id), "Id cannot be blank for deployed service.");
                return deployService + "." + id;
            }
        }
    }

    public ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult createNew(ToolDecoratorInterpreter.ToolDecoratorEffect.CreateNewToolDecorator createNewToolDecorator) {
        var mcpClient = createNewToolDecorator.mcpClient();
        var deployService = createNewToolDecorator.deployService();
        try {
            McpSchema.ListToolsResult listToolsResult = mcpClient.listTools();

            List<ToolCallbackProvider> providersCreated = new ArrayList<>();
            Set<String> toolsAdded = new HashSet<>();
            Set<String> toolsRemoved = new HashSet<>();
            Set<String> tools = new HashSet<>();
            List<McpServerFeatures.SyncToolSpecification> toAddTools = new ArrayList<>();
            Set<String> toolErrors = new HashSet<>();

            var t = toToolCallbackProvider(listToolsResult, mcpClient, deployService);

            for (var tcp : t) {
                if (tcp.provider() == null) {
//                  there is no existing!
                    addToErr(tcp, toolErrors);
                } else {
                    Arrays.stream(tcp.provider().getToolCallbacks())
                            .forEach(tc -> {
                                toAddTools.add(McpToolUtils.toSyncToolSpecification(tc));
                                toolsAdded.add(tc.getToolDefinition().name());
                                tools.add(tc.getToolDefinition().name());
                            });
                    log.info("Adding new toolfor callback {}", tcp.toolName().name());
                    providersCreated.add(tcp.provider());
                }
            }

            return ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult.builder()
                    .toolsAdded(toolsAdded)
                    .toolState(createNewToolDecorator.toolState())
                    .toAddTools(toAddTools)
                    .toolsRemoved(toolsRemoved)
                    .tools(tools)
                    .providers(providersCreated)
                    .toRemoveTools(toolsRemoved)
                    .build();
        } catch (Exception e) {
            return parseToolExceptionFail(e, mcpClient, deployService, createNewToolDecorator.toolState());
        }
    }

    public ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult updateExisting(ToolDecoratorInterpreter.ToolDecoratorEffect.UpdatingExistingToolDecorator updatingExistingToolDecorator) {
        var removedState = updatingExistingToolDecorator.removedState();
        var deployService = updatingExistingToolDecorator.deployService();
        var m = updatingExistingToolDecorator.m();

        Map<String, ToolDecoratorService.ToolCallbackDescriptor> existing = toExistingToolCallbackProviders(removedState);

        try {
            McpSchema.ListToolsResult listToolsResult = m.listTools();

            List<ToolCallbackProvider> providersCreated = new ArrayList<>();
            Set<String>  toolsAdded = new HashSet<>();
            Set<String>  toolsRemoved = new HashSet<>();
            Set<String>  toRemoveTools = new HashSet<>();
            Set<String>  tools = new HashSet<>();
            Set<String> toolErrors = new HashSet<>();
            List<McpServerFeatures.SyncToolSpecification> toAddTools = new ArrayList<>();


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
                                toRemoveTools.add(n.getKey());
                            }
                            tools.add(n.getKey());
                            providersCreated.add(tcp);
                            Arrays.stream(tcp.getToolCallbacks())
                                    .forEach(tc -> toAddTools.add(McpToolUtils.toSyncToolSpecification(tc)));
                        }, () -> {
                            log.error("Unable to create tool callback provider for {}.", n.getKey());
                            addToErr(p, toolErrors);
                        });
            }

            for (var n : existing.entrySet()) {
                if (!newTools.containsKey(n.getKey())) {
                    log.info("Removing tool {} from {}", n.getKey(), newTools.keySet());
                    toRemoveTools.add(n.getKey());
//                    mcpSyncServerDelegate.removeTool(n.getKey());
                    toolsRemoved.add(n.getKey());
                }
            }

            return ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult.builder()
                    .toolsAdded(toolsAdded)
                    .toolsRemoved(toolsRemoved)
                    .tools(tools)
                    .providers(providersCreated)
                    .toRemoveTools(toRemoveTools)
                    .toAddTools(toAddTools)
                    .toolState(updatingExistingToolDecorator.removedState())
                    .build();
        } catch (Exception e) {
            return parseToolExceptionFail(e, m, existing, deployService, updatingExistingToolDecorator.removedState());
        }
    }

    private List<ToolDecoratorService.CreateToolCallbackProviderResult> toToolCallbackProvider(McpSchema.ListToolsResult listToolsResult,
                                                                                               SetClients.DelegateMcpClient mcpSyncClient,
                                                                                               McpServerToolStates.DeployedService deployService) {
        return listToolsResult.tools().stream()
                .map(t -> createToolCallbackProvider(mcpSyncClient, t, deployService))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private @NotNull ToolDecoratorService.CreateToolCallbackProviderResult createToolCallbackProvider(SetClients.DelegateMcpClient mcpSyncClient,
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

    private @NotNull PassthroughFunctionToolCallback buildPassThroughToolCallback(SetClients.DelegateMcpClient mcpSyncClient,
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

    private static ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult parseToolExceptionFail(Exception e,
                                                                                                           SetClients.DelegateMcpClient mcpClient,
                                                                                                           DeployedService deployService,
                                                                                                           ToolDecoratorService.McpServerToolState toolState) {
        log.error("Error when attempting to retrieve tools: {}", e.getMessage(), e);
        mcpClient.setError(e.getMessage());
        return ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult.builder()
                .err(e.getMessage())
                .toolState(toolState)
                .build();
    }

    private static ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult parseToolExceptionFail(Exception e,
                                                                                                           SetClients.DelegateMcpClient mcpClient,
                                                                                                           Map<String, ToolDecoratorService.ToolCallbackDescriptor> existing,
                                                                                                           McpServerToolStates.DeployedService deployService,
                                                                                                           ToolDecoratorService.McpServerToolState toolState) {
        log.error("Error when attempting to retrieve tools: {}", e.getMessage(), e);
        mcpClient.setError(e.getMessage());
        return ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult.builder()
                .toolsRemoved(existing.keySet())
                .err(e.getMessage())
                .toolState(toolState)
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

    public ToolDecoratorInterpreter.ToolDecoratorResult.SetMcpClientResult doSetMcpClient(McpSyncClient m,
                                                                                          McpServerToolStates.DeployedService deployService,
                                                                                          ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return new ToolDecoratorInterpreter.ToolDecoratorResult.SetMcpClientResult(this.setClients.doSetMcpClient(m, deployService, mcpServerToolState));
    }
}
