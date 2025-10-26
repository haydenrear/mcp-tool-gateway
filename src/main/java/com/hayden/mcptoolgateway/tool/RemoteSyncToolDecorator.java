package com.hayden.mcptoolgateway.tool;

import org.springframework.stereotype.Component;

@Component
public class RemoteSyncToolDecorator implements ToolDecorator {
    @Override
    public ToolDecoratorToolStateUpdate decorate(ToolDecoratorState newMcpServerState) {
        return null;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
