package com.hayden.mcptoolgateway.tool.tool_state;

import com.hayden.mcptoolgateway.tool.ToolDecorator;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.utilitymodule.concurrent.striped.StripedLock;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.transport.AuthResolver;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
@Slf4j
public class McpServerToolStates {

    private final SetClients setClients;

    private final McpSyncServerDelegate syncServerDelegate;

    private final Map<String, ToolDecoratorService.McpServerToolState> mcpServerToolStates = new ConcurrentHashMap<>();

    private volatile boolean didInitialize = false;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public Map<String, ToolDecoratorService.McpServerToolState> copyOf() {
        return new HashMap<>(mcpServerToolStates);
    }

     public void doOverState(Runnable toDo) {
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

    public <U> U doOverState(Supplier<U> toDo) {
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

    @StripedLock
    public ToolDecoratorService.SetSyncClientResult setMcpClient(String deployService,
                                                                 ToolDecoratorService.McpServerToolState mcpServerToolState) {
        ;
        return setClients.setMcpClient(getAuthDeployedService(deployService), mcpServerToolState);
    }

    public static @NotNull DeployedService getAuthDeployedService(String deployService) {
        return new DeployedService(deployService, AuthResolver.resolveUserOrDefault());
    }

    @StripedLock
    public ToolDecoratorService.SetSyncClientResult setMcpClient(DeployedService deployService,
                                                                 ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return setClients.setMcpClient(deployService, mcpServerToolState);
    }

    @StripedLock
    public ToolDecoratorService.SetSyncClientResult setMcpClient(DeployedService deployService,
                                                                 ToolDecoratorService.McpServerToolState mcpServerToolState,
                                                                 NamedClientMcpTransport namedTransport) {
        return setClients.setMcpClient(deployService, mcpServerToolState, namedTransport);
    }

    @StripedLock
    public ToolDecoratorService.SetSyncClientResult createSetClientErr(String service, DynamicMcpToolCallbackProvider.McpError m,
                                                                       ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return createSetClientErr(getAuthDeployedService(service), m, mcpServerToolState);
    }

    @StripedLock
    public ToolDecoratorService.SetSyncClientResult createSetClientErr(DeployedService service, DynamicMcpToolCallbackProvider.McpError m, ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return setClients.createSetClientErr(service, m, mcpServerToolState);
    }

    public void addUpdateToolState(String name, ToolDecoratorService.McpServerToolState mcpServerToolState) {
        this.mcpServerToolStates.put(name, mcpServerToolState);
    }

    public void addUpdateToolState(Map<String, ToolDecoratorService.McpServerToolState> states) {
        this.mcpServerToolStates.putAll(states);
    }

    public void addUpdateToolState(ToolDecorator.ToolDecoratorToolStateUpdate toolDecoratorToolStateUpdate) {
        this.addUpdateToolState(toolDecoratorToolStateUpdate.name(), toolDecoratorToolStateUpdate.toolStates());
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
}
