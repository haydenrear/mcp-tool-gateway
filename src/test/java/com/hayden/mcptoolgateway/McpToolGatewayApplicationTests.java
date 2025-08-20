package com.hayden.mcptoolgateway;

import com.hayden.mcptoolgateway.fn.RedeployFunction;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import org.junit.Before;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
@ActiveProfiles("test")
class McpToolGatewayApplicationTests {

    @Autowired
    DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;
    @MockitoBean
    RedeployFunction redeployFunction;

    @BeforeEach
    public void setupRedeploy() {
        AtomicInteger i = new AtomicInteger(0);
        Mockito.when(redeployFunction.performRedeploy(any()))
                .thenAnswer(invocation -> {
                    if (i.getAndIncrement() == 0) {
                        return RedeployFunction.RedeployDescriptor.builder().isSuccess(true).build();
                    }

                    if (i.get() == 1) {

                    }

                    return RedeployFunction.RedeployDescriptor.builder().isSuccess(true).build();
                });
    }

    @Test
    void contextLoads() {
    }

    @Test
    void containsTestJarActiveClient() {
        assertThat(new File("build/libs/test-mcp-server.jar").exists()).isTrue();
        assertThat(dynamicMcpToolCallbackProvider.containsClient("test-mcp-server")).isTrue();
        assertThat(dynamicMcpToolCallbackProvider.containsActiveClient("test-mcp-server")).isTrue();
    }

    @Test
    void redeployTestMcpServer() {
        assertThat(new File("build/libs/test-mcp-server.jar").exists()).isTrue();
        assertThat(dynamicMcpToolCallbackProvider.containsClient("test-mcp-server")).isTrue();
        assertThat(dynamicMcpToolCallbackProvider.containsActiveClient("test-mcp-server")).isTrue();
    }

}
