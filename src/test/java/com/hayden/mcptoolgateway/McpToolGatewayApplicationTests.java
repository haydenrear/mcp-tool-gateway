package com.hayden.mcptoolgateway;

import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.*;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;

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
    }

}
