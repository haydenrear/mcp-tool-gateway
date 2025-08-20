package com.hayden.mcptoolgateway;

import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class McpToolGatewayApplicationTests {

    @Autowired
    DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;

    @Test
    void contextLoads() {
    }

    @Test
    void containsTestJar() {
        assertThat(new File("build/libs/test-mcp-server.jar").exists()).isTrue();
        assertThat(dynamicMcpToolCallbackProvider.containsClient("test-mcp-server")).isTrue();
        assertThat(dynamicMcpToolCallbackProvider.containsActiveClient("test-mcp-server")).isTrue();
    }

}
