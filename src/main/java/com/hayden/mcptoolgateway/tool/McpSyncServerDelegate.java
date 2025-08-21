package com.hayden.mcptoolgateway.tool;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class McpSyncServerDelegate {

    @Autowired
    McpSyncServer mcpSyncServer;

    public synchronized void addTool(McpServerFeatures.SyncToolSpecification toolHandler) {
        mcpSyncServer.addTool(toolHandler);
    }

    public synchronized void removeTool(String toolName) {
        mcpSyncServer.removeTool(toolName);
    }

    public synchronized void notifyToolsListChanged() {
        mcpSyncServer.notifyToolsListChanged();
    }
}
