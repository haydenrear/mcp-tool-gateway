package com.hayden.mcptoolgateway.tool.tool_state;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class McpSyncServerDelegate {

    McpSyncServer mcpSyncServer;

    @Autowired
    public void setMcpSyncServer(McpSyncServer mcpSyncServer) {
        this.mcpSyncServer = mcpSyncServer;
    }

    public synchronized void addTool(McpServerFeatures.SyncToolSpecification toolHandler) {
        try {
            log.info("Adding tool to sync server - {}", toolHandler.tool().name());
            mcpSyncServer.addTool(toolHandler);
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    public synchronized void removeTool(String toolName) {
        try {
            log.info("Removing tool to sync server - {}", toolName);
            mcpSyncServer.removeTool(toolName);
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    public synchronized void notifyToolsListChanged() {
        mcpSyncServer.notifyToolsListChanged();
    }
}
