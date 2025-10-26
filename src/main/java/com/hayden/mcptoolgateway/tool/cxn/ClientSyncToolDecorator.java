package com.hayden.mcptoolgateway.tool.cxn;

import com.hayden.mcptoolgateway.tool.ToolDecorator;
import org.springframework.stereotype.Component;

@Component
public class ClientSyncToolDecorator implements ToolDecorator {
    @Override
    public ToolDecoratorToolStateUpdate decorate(ToolDecoratorState newMcpServerState) {
        return null;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
