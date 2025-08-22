package com.hayden.mcptoolgateway;

import com.hayden.mcptoolgateway.config.WritableInput;
import com.hayden.mcptoolgateway.fn.RedeployFunction;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.SneakyThrows;
import org.junit.experimental.theories.Theories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.shaded.com.fasterxml.jackson.core.JsonProcessingException;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.hayden.mcptoolgateway.tool.ToolDecoratorService.REDEPLOY_MCP_SERVER;
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
    void containsTestJarActiveClient() {
        assertThat(new File("ctx_bin/test-mcp-server.jar").exists()).isTrue();
        assertThat(dynamicMcpToolCallbackProvider.containsClient("test-mcp-server")).isTrue();
        assertThat(dynamicMcpToolCallbackProvider.containsActiveClient("test-mcp-server")).isTrue();
    }

}
