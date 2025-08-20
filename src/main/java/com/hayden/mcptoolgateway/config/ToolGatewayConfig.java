package com.hayden.mcptoolgateway.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.utilitymodule.ByteUtility;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import lombok.SneakyThrows;
import org.apache.commons.compress.utils.ByteUtils;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.autoconfigure.configurer.McpSyncClientConfigurer;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.mcp.customizer.McpSyncClientCustomizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import reactor.core.publisher.Mono;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Configuration
public class ToolGatewayConfig {

    @Autowired
    private ToolGatewayConfigProperties toolGatewayConfigProperties;

    private Map<String, ServerParameters> resourceToServerParameters() {
        try {
            Map<String, Map<String, McpStdioClientProperties.Parameters>> stdioConnection = new ObjectMapper().readValue(
                    new PathMatchingResourcePatternResolver().getResource(toolGatewayConfigProperties.mcpServersJsonLocation)
                                                             .getFile(),
                    new TypeReference<>() {});

            Map<String, McpStdioClientProperties.Parameters> mcpServerJsonConfig = stdioConnection.entrySet().iterator().next().getValue();

            return mcpServerJsonConfig.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, kv -> {
                McpStdioClientProperties.Parameters parameters = kv.getValue();
                return ServerParameters.builder(parameters.command())
                                       .args(parameters.args())
                                       .env(parameters.env())
                                       .build();
            }));
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to read stdio connection resource", e);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public McpSyncClientConfigurer mcpSyncClientConfigurer(@Autowired(required = false) List<McpSyncClientCustomizer> customizers) {
        if (customizers == null) {
            customizers = new ArrayList<>();
        }
        return new McpSyncClientConfigurer(customizers);
    }

    @Bean
    @Primary
    public List<NamedClientMcpTransport> namedTransports() {
        return resourceToServerParameters().entrySet()
                .stream()
                .map(e -> new NamedClientMcpTransport(e.getKey(), new StdioClientTransport(e.getValue())))
                .toList();
    }

    @SneakyThrows
    @Bean
    @Profile({"test"})
    public StdioServerTransportProvider transportProvider(ObjectMapper objectMapper,
                                                          WritableInput writableInput) {

        return new StdioServerTransportProvider(objectMapper, writableInput.input(), new OutputStream() {
            @Override
            public void write(int b) {
                System.out.print((char) b);
            }
        });
    }

}
