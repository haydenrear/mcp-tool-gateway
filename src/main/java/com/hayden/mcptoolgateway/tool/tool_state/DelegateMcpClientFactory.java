package com.hayden.mcptoolgateway.tool.tool_state;

import com.hayden.mcptoolgateway.security.AuthResolver;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DelegateMcpClientFactory {

    private final AuthResolver authResolver;

    public SetClients.DelegateMcpClient clientFactory(ToolDecoratorService.McpServerToolState toolState) {
        if (toolState == null)
            return new SetClients.SingleDelegateMcpClient();
        if (toolState.deployableMcpServer().isHasMany())
            return new SetClients.MultipleClientDelegateMcpClient(authResolver);
        else
            return new SetClients.SingleDelegateMcpClient();
    }

}
