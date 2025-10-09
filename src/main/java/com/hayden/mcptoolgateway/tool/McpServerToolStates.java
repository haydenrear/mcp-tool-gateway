package com.hayden.mcptoolgateway.tool;

import com.hayden.utilitymodule.concurrent.striped.StripedLock;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
@RequiredArgsConstructor
public class McpServerToolStates {

    private final SetClients setClients;

    private final Map<String, ToolDecoratorService.McpServerToolState> mcpServerToolStates = new ConcurrentHashMap<>();

    private volatile boolean didInitialize = false;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public Map<String, ToolDecoratorService.McpServerToolState> copyOf() {
        return new HashMap<>(mcpServerToolStates);
    }

    public void lock() {
        lock.writeLock().lock();
    }

    public void unlock() {
        lock.writeLock().unlock();
    }

    public void initialized() {
        didInitialize = true;
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

    public boolean clientNotInitialized(String service) {
        return setClients.clientNotInitialized(service);
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

    @StripedLock
    public ToolDecoratorService.SetSyncClientResult setMcpClient(String deployService, ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return setClients.setMcpClient(deployService, mcpServerToolState);
    }

    @StripedLock
    public ToolDecoratorService.SetSyncClientResult createSetClientErr(String service, DynamicMcpToolCallbackProvider.McpError m, ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return setClients.createSetClientErr(service, m, mcpServerToolState);
    }
}
