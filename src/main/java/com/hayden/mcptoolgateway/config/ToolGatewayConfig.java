package com.hayden.mcptoolgateway.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.mcptoolgateway.security.IdentityResolver;
import com.hayden.mcptoolgateway.security.OAuth2AuthorizationServerResolver;
import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.utilitymodule.MapFunctions;
import com.hayden.utilitymodule.stream.StreamUtil;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.autoconfigure.configurer.McpSyncClientConfigurer;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpSseClientProperties;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.mcp.customizer.McpSyncClientCustomizer;
import org.springframework.ai.mcp.server.autoconfigure.McpServerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.io.*;
import java.util.*;
import java.util.stream.Stream;

import static io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider.DEFAULT_SSE_ENDPOINT;

@Slf4j
@Configuration
public class ToolGatewayConfig {

    @Autowired
    private ToolGatewayConfigProperties toolGatewayConfigProperties;

    private Map<String, McpSseClientProperties.SseParameters> resourceToHttpServerParameters() {
        try {
            if (!toolGatewayConfigProperties.hasHttpServers())
                return new HashMap<>();
            Map<String, Map<String, McpSseClientProperties.SseParameters>> stdioConnection = new ObjectMapper().readValue(
                    new PathMatchingResourcePatternResolver().getResource(toolGatewayConfigProperties.mcpHttpServersJsonLocation)
                            .getContentAsByteArray(),
                    new TypeReference<>() {});

            var remove = Optional.ofNullable(stdioConnection.remove("mcpServers"))
                    .orElseGet(() -> stdioConnection.remove("mcp-server"));
            if (!stdioConnection.isEmpty())
                throw new RuntimeException("Found multiple keys in config %s!".formatted(stdioConnection));

            return MapFunctions.CollectMap(
                    remove.entrySet()
                            .stream()
                            .map(e -> {
                                if (e.getValue().sseEndpoint() == null)
                                    return Map.entry(e.getKey(), new McpSseClientProperties.SseParameters(e.getValue().url(), DEFAULT_SSE_ENDPOINT));

                                return e;
                            }));
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to read stdio connection resource", e);
        }
    }

    private Map<String, ServerParameters> resourceToStdioServerParameters() {
        try {
            if (!toolGatewayConfigProperties.hasStdioServers())
                return new HashMap<>();
            Map<String, Map<String, McpStdioClientProperties.Parameters>> stdioConnection = new ObjectMapper().readValue(
                    new PathMatchingResourcePatternResolver().getResource(toolGatewayConfigProperties.mcpStdioServersJsonLocation)
                            .getContentAsByteArray(),
                    new TypeReference<>() {});

            Map<String, McpStdioClientProperties.Parameters> remove = Optional.ofNullable(stdioConnection.remove("mcpServers"))
                    .orElseGet(() -> stdioConnection.remove("mcp-server"));
            if (!stdioConnection.isEmpty())
                throw new RuntimeException("Found multiple keys in config %s!".formatted(stdioConnection));
            return MapFunctions.CollectMap(Optional.ofNullable(remove)
                    .stream()
                    .flatMap(mcpServersJsonConfig -> {
                        return mcpServersJsonConfig.entrySet().stream()
                                .map(kv -> {
                                    McpStdioClientProperties.Parameters parameters = kv.getValue();
                                    return Map.entry(kv.getKey(), ServerParameters.builder(parameters.command())
                                            .args(parameters.args())
                                            .env(parameters.env())
                                            .build());
                                });
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
    public List<NamedClientMcpTransport> namedTransports(ObjectMapper objectMapper,
                                                         IdentityResolver authResolver) {
        var http = resourceToHttpServerParameters().entrySet()
                .stream()
                .peek(e -> {
                    if (!toolGatewayConfigProperties.getDeployableMcpServers().containsKey(e.getKey())) {
                        throw new RuntimeException("Must have MCP server config for %s".formatted(e.getKey()));
                    }
                })
                .map(e -> new NamedClientMcpTransport(
                        e.getKey(),
                        AuthAwareHttpSseClientTransport.authAwareBuilder(e.getValue().url())
                                .objectMapper(objectMapper)
                                .authResolver(authResolver)
                                .sseEndpoint(e.getKey())
                                .configProperties(toolGatewayConfigProperties.deployableMcpServers.get(e.getKey()))
                                .build()));
        var stdio = resourceToStdioServerParameters().entrySet()
                .stream()
                .map(e -> new NamedClientMcpTransport(e.getKey(), new StdioClientTransport(e.getValue())));

        var all = Stream.concat(http, stdio).toList();
        return all;
    }

    @SneakyThrows
    @Bean
    @Profile({"rollback-tests"})
    public StdioServerTransportProvider transportProvider(ObjectMapper objectMapper,
                                                          WritableInput writableInput) {

        return new StdioServerTransportProvider(objectMapper, writableInput.input(), new OutputStream() {
            @Override
            public void write(int b) {
                System.out.print((char) b);
            }
        });
    }

    @Bean
    @ConditionalOnProperty(value = "gateway.enable-redeployable", havingValue = "true", matchIfMissing = true)
    public CommandLineRunner initializeCodeExecutions(ToolGatewayConfigProperties props,
                                                      RedeployFunction graphqlRedeploy) {
        StreamUtil.toStream(props.deployableMcpServers.values())
                .forEach(graphqlRedeploy::register);
        return args -> {};
    }

    @Bean
    @ConditionalOnProperty(name = "spring.ai.mcp.server.stdio", havingValue = "true")
    public StdioServerTransportProvider stdioProvider(ObjectMapper objectMapper) {
        return new StdioServerTransportProvider(objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "spring.ai.mcp.server.stdio", havingValue = "false")
    public WebMvcSseServerTransportProvider httpProvider(
            ObjectMapper om, McpServerProperties serverProperties) {
        return new WebMvcSseServerTransportProvider(om, serverProperties.getBaseUrl(),
                serverProperties.getSseMessageEndpoint(), serverProperties.getSseEndpoint());
    }

    @Bean
    @ConditionalOnProperty(name = "spring.ai.mcp.server.stdio", havingValue = "false")
    public RouterFunction<ServerResponse> mvcMcpRouterFunction(WebMvcSseServerTransportProvider transportProvider) {
        return transportProvider.getRouterFunction();
    }

}
