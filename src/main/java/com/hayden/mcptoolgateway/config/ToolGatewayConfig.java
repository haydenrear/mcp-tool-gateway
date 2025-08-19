package com.hayden.mcptoolgateway.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.autoconfigure.configurer.McpSyncClientConfigurer;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.mcp.customizer.McpSyncClientCustomizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
public class ToolGatewayConfig {

    private Map<String, ServerParameters> resourceToServerParameters() {
        try {
            Map<String, Map<String, McpStdioClientProperties.Parameters>> stdioConnection = new ObjectMapper().readValue(
                    new PathMatchingResourcePatternResolver().getResource("classpath:mcp-servers/servers.json")
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

}
