package com.hayden.mcptoolgateway.tool.tool_state;

import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import org.springframework.stereotype.Service;

@Service
public class DelegateMcpClientFactory {

    public SetClients.DelegateMcpClient clientFactory(ToolDecoratorService.McpServerToolState toolState) {
        if (toolState.deployableMcpServer().isHasMany())
            return new SetClients.MultipleClientDelegateMcpClient();
        else
            return new SetClients.SingleDelegateMcpClient();
    }

}
