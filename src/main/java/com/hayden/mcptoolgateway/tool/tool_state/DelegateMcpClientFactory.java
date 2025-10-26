package com.hayden.mcptoolgateway.tool.tool_state;

import com.hayden.mcptoolgateway.security.AuthResolver;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.utilitymodule.stream.StreamUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DelegateMcpClientFactory {

    private final AuthResolver authResolver;

    public SetClients.DelegateMcpClient clientFactory(ToolDecoratorService.McpServerToolState toolState) {
        if (toolState == null) {
            return getSingleDelegateMcpClient(toolState, authResolver);
        }
        if (toolState.deployableMcpServer().isHasMany())
            return new SetClients.MultipleClientDelegateMcpClient(authResolver, toolState);
        else
            return getSingleDelegateMcpClient(toolState, authResolver);
    }

    public static SetClients.SingleDelegateMcpClient getSingleDelegateMcpClient(ToolDecoratorService.McpServerToolState toolState,
                                                                                AuthResolver resolver) {
        var s = new SetClients.SingleDelegateMcpClient(
                StreamUtil.toStream(toolState.afterToolCallback())
                        .collect(Collectors.toCollection(ArrayList::new)),
                StreamUtil.toStream(toolState.beforeToolCallback())
                        .collect(Collectors.toCollection(ArrayList::new)),
                resolver);
        return s;
    }

}
