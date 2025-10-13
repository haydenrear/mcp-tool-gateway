package com.hayden.mcptoolgateway.tool.tool_state;

import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DelegateMcpClientFactory {

    public SetClients.DelegateMcpClient clientFactory(ToolDecoratorService.McpServerToolState toolState) {
        if (toolState == null)
            return new SetClients.MultipleClientDelegateMcpClient();
        if (toolState.deployableMcpServer().isHasMany())
            return new SetClients.MultipleClientDelegateMcpClient();
        else
            return new SetClients.SingleDelegateMcpClient();
    }

}
