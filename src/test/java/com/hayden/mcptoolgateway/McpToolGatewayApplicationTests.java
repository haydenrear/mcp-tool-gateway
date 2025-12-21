package com.hayden.mcptoolgateway;

import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.*;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
@ActiveProfiles({"test"})
class McpToolGatewayApplicationTests {

    @Autowired
    DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;
    @Autowired
    ToolDecoratorService toolDecoratorService;

    @Test
    void containsTestJarActiveClient() {
        toolDecoratorService.doPerformInit();
        assertThat(new File("ctx_bin/test-mcp-server.jar").exists()).isTrue();
        assertThat(dynamicMcpToolCallbackProvider.containsClient("test-rollback-server")).isTrue();
        assertThat(dynamicMcpToolCallbackProvider.containsActiveClient("test-rollback-server")).isTrue();
        assertThat(dynamicMcpToolCallbackProvider.containsClient("test-rollback-server-2")).isFalse();
        assertThat(dynamicMcpToolCallbackProvider.containsActiveClient("test-rollback-server-2")).isFalse();
        var added = toolDecoratorService.createAddServer(new ToolDecoratorService.AddToolSearch("test-rollback-server-2", null));
        assertThat(dynamicMcpToolCallbackProvider.containsClient("test-rollback-server-2")).isTrue();
        assertThat(dynamicMcpToolCallbackProvider.containsActiveClient("test-rollback-server-2")).isTrue();
        assertThat(added.underlying().tools().stream().anyMatch(a -> a.contains("doSomethingAgain"))).isTrue();
    }

    @Test
    void testBuilder() {
    }

}
