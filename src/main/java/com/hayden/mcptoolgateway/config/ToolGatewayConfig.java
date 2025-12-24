package com.hayden.mcptoolgateway.config;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.McpServerFeatures.SyncCompletionSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerChangeNotificationProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.CollectionUtils;
import org.springframework.web.context.support.StandardServletEnvironment;
import com.hayden.mcptoolgateway.security.IdentityResolver;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.utilitymodule.MapFunctions;
import com.hayden.utilitymodule.stream.StreamUtil;
import io.modelcontextprotocol.client.transport.AuthAwareHttpStreamableClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.common.autoconfigure.configurer.McpSyncClientConfigurer;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.mcp.customizer.McpSyncClientCustomizer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.MimeType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.io.OutputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Slf4j
@Configuration
@EnableConfigurationProperties({McpServerProperties.class, McpServerChangeNotificationProperties.class})
public class ToolGatewayConfig {

    @Autowired
    private ToolGatewayConfigProperties toolGatewayConfigProperties;

    private static final String DEFAULT_STREAMABLE_ENDPOINT = "/mcp";

    @Bean(name = {"mcpJsonMapper", "jsonMapper"})
    public McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    private Map<String, McpStreamableHttpClientProperties.ConnectionParameters> resourceToHttpServerParameters() {
        try {
            if (!toolGatewayConfigProperties.hasHttpServers())
                return new HashMap<>();
            Map<String, Map<String, Map<String, String>>> stdioConnection = new ObjectMapper().readValue(
                    new PathMatchingResourcePatternResolver().getResource(toolGatewayConfigProperties.mcpHttpServersJsonLocation)
                            .getContentAsByteArray(),
                    new TypeReference<>() {});

            var remove = Optional.ofNullable(stdioConnection.remove("mcpServers"))
                    .orElseGet(() -> stdioConnection.remove("mcp-server"));
            if (!stdioConnection.isEmpty())
                throw new RuntimeException("Found multiple keys in config %s!".formatted(stdioConnection));

            return MapFunctions.CollectMap(Optional.ofNullable(remove).orElseGet(Collections::emptyMap)
                    .entrySet()
                    .stream()
                    .map(e -> {
                        Map<String, String> fields = Optional.ofNullable(e.getValue()).orElseGet(Collections::emptyMap);
                        String url = fields.get("url");
                        String endpoint = Optional.ofNullable(fields.get("endpoint"))
                                .or(() -> Optional.ofNullable(fields.get("sseEndpoint")))
                                .orElse(null);
                        if (endpoint == null) {
                            endpoint = DEFAULT_STREAMABLE_ENDPOINT;
                        }
                        if (url == null) {
                            throw new RuntimeException("Missing url for MCP server %s".formatted(e.getKey()));
                        }
                        return Map.entry(e.getKey(),
                                new McpStreamableHttpClientProperties.ConnectionParameters(url, endpoint));
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

            if (stdioConnection.isEmpty())
                return new HashMap<>();

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
    public List<NamedClientMcpTransport> namedTransports(McpJsonMapper objectMapper,
                                                         ObjectMapper om,
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
                        AuthAwareHttpStreamableClientTransport.authAwareBuilder(e.getValue().url())
                                .objectMapper(om)
                                .authResolver(authResolver)
                                .endpoint(e.getValue().endpoint())
                                .configProperties(toolGatewayConfigProperties.deployableMcpServers.get(e.getKey()))
                                .build()));
        var stdio = resourceToStdioServerParameters().entrySet()
                .stream()
                .map(e -> new NamedClientMcpTransport(e.getKey(), new StdioClientTransport(e.getValue(), objectMapper)));

        var all = Stream.concat(http, stdio).toList();
        return all;
    }

    @SneakyThrows
    @Bean
    @Profile({"rollback-tests"})
    public StdioServerTransportProvider transportProvider(McpJsonMapper objectMapper,
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
    public StdioServerTransportProvider stdioProvider(McpJsonMapper objectMapper) {
        return new StdioServerTransportProvider(objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "spring.ai.mcp.server.stdio", havingValue = "false")
    public WebMvcStreamableServerTransportProvider httpProvider(
            McpJsonMapper om, McpServerProperties serverProperties) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(om)
                .mcpEndpoint(DEFAULT_STREAMABLE_ENDPOINT)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "spring.ai.mcp.server.stdio", havingValue = "false")
    public RouterFunction<ServerResponse> mvcMcpRouterFunction(WebMvcStreamableServerTransportProvider transportProvider) {
        return transportProvider.getRouterFunction();
    }

    @Bean
    @ConditionalOnMissingBean
    public McpSchema.ServerCapabilities.Builder capabilitiesBuilder() {
        return McpSchema.ServerCapabilities.builder();
    }

    @SneakyThrows
    @Bean("mcpSyncServer")
    @Primary
    public McpSyncServer mcpSyncServer(McpServerTransportProviderBase transportProvider,
                                                  McpSchema.ServerCapabilities.Builder capabilitiesBuilder,
                                                  McpServerProperties serverProperties,
                                                  ObjectMapper objectMapper,
                                                  McpServerChangeNotificationProperties changeNotificationProperties,
                                                  ObjectProvider<List<McpServerFeatures.SyncToolSpecification>> tools,
                                                  ObjectProvider<List<SyncResourceTemplateSpecification>> resourceTemplates,
                                                  ObjectProvider<List<McpServerFeatures.SyncResourceSpecification>> resources,
                                                  ObjectProvider<List<McpServerFeatures.SyncPromptSpecification>> prompts,
                                                  ObjectProvider<List<McpServerFeatures.SyncCompletionSpecification>> completions,
                                                  ObjectProvider<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeConsumers,
                                                  List<ToolCallbackProvider> toolCallbackProvider,
                                                  @Lazy ToolDecoratorService toolDecoratorService,
                                                  Environment environment) {
        McpSchema.Implementation serverInfo = new McpSchema.Implementation(serverProperties.getName(),
                serverProperties.getVersion());

        GatewayMcpServer.SyncSpecification<?> serverBuilder;
        if (transportProvider instanceof McpStreamableServerTransportProvider) {
            serverBuilder = GatewayMcpServer.sync((McpStreamableServerTransportProvider) transportProvider);
        }
        else {
            serverBuilder = GatewayMcpServer.sync((McpServerTransportProvider) transportProvider);
        }
        serverBuilder.serverInfo(serverInfo);

        // Tools
        if (serverProperties.getCapabilities().isTool()) {
            log.info("Enable tools capabilities, notification: "
                    + changeNotificationProperties.isToolChangeNotification());
            capabilitiesBuilder.tools(changeNotificationProperties.isToolChangeNotification());

            List<SyncToolSpecification> toolSpecifications = new ArrayList<>(
                    tools.stream().flatMap(List::stream).toList());

            if (!CollectionUtils.isEmpty(toolSpecifications)) {
                serverBuilder.tools(toolSpecifications);
                log.info("Registered tools: " + toolSpecifications.size());
            }
        }

        // Resources
        if (serverProperties.getCapabilities().isResource()) {
            log.info("Enable resources capabilities, notification: "
                    + changeNotificationProperties.isResourceChangeNotification());
            capabilitiesBuilder.resources(false, changeNotificationProperties.isResourceChangeNotification());

            List<SyncResourceSpecification> resourceSpecifications = resources.stream().flatMap(List::stream).toList();
            if (!CollectionUtils.isEmpty(resourceSpecifications)) {
                serverBuilder.resources(resourceSpecifications);
                log.info("Registered resources: " + resourceSpecifications.size());
            }
        }

        // Resources Templates
        if (serverProperties.getCapabilities().isResource()) {
            log.info("Enable resources templates capabilities, notification: "
                    + changeNotificationProperties.isResourceChangeNotification());
            capabilitiesBuilder.resources(false, changeNotificationProperties.isResourceChangeNotification());

            List<SyncResourceTemplateSpecification> resourceTemplateSpecifications = resourceTemplates.stream()
                    .flatMap(List::stream)
                    .toList();
            if (!CollectionUtils.isEmpty(resourceTemplateSpecifications)) {
                serverBuilder.resourceTemplates(resourceTemplateSpecifications);
                log.info("Registered resource templates: " + resourceTemplateSpecifications.size());
            }
        }

        // Prompts
        if (serverProperties.getCapabilities().isPrompt()) {
            log.info("Enable prompts capabilities, notification: "
                    + changeNotificationProperties.isPromptChangeNotification());
            capabilitiesBuilder.prompts(changeNotificationProperties.isPromptChangeNotification());

            List<SyncPromptSpecification> promptSpecifications = prompts.stream().flatMap(List::stream).toList();
            if (!CollectionUtils.isEmpty(promptSpecifications)) {
                serverBuilder.prompts(promptSpecifications);
                log.info("Registered prompts: " + promptSpecifications.size());
            }
        }

        // Completions
        if (serverProperties.getCapabilities().isCompletion()) {
            log.info("Enable completions capabilities");
            capabilitiesBuilder.completions();

            List<SyncCompletionSpecification> completionSpecifications = completions.stream()
                    .flatMap(List::stream)
                    .toList();
            if (!CollectionUtils.isEmpty(completionSpecifications)) {
                serverBuilder.completions(completionSpecifications);
                log.info("Registered completions: " + completionSpecifications.size());
            }
        }

        rootsChangeConsumers.ifAvailable(consumer -> {
            BiConsumer<McpSyncServerExchange, List<McpSchema.Root>> syncConsumer = (exchange, roots) -> consumer
                    .accept(exchange, roots);
            serverBuilder.rootsChangeHandler(syncConsumer);
            log.info("Registered roots change consumer");
        });

        serverBuilder.capabilities(capabilitiesBuilder.build());

        serverBuilder.instructions(serverProperties.getInstructions());

        serverBuilder.requestTimeout(serverProperties.getRequestTimeout());

        if (environment instanceof StandardServletEnvironment) {
            serverBuilder.immediateExecution(true);
        }


        McpSyncServer built = serverBuilder.build();

        if (built.getAsyncServer() instanceof GatewayMcpAsyncServer g) {
            g.setToolDecoratorService(toolDecoratorService);
        }

        return built;
    }

    @Bean
    public List<McpServerFeatures.SyncToolSpecification> syncTools(ObjectProvider<List<ToolCallback>> toolCalls,
                                                                   List<ToolCallback> toolCallbacksList,
                                                                   McpServerProperties serverProperties) {

        List<ToolCallback> tools = new ArrayList<>(toolCalls.stream().flatMap(List::stream).toList());

        if (!CollectionUtils.isEmpty(toolCallbacksList)) {
            tools.addAll(toolCallbacksList);
        }

        return this.toSyncToolSpecifications(tools, serverProperties);
    }

    private List<McpServerFeatures.SyncToolSpecification> toSyncToolSpecifications(List<ToolCallback> tools,
                                                                                   McpServerProperties serverProperties) {

        // De-duplicate tools by their name, keeping the first occurrence of each tool
        // name
        return tools.stream() // Key: tool name
                .collect(Collectors.toMap(tool -> tool.getToolDefinition().name(), tool -> tool, // Value:
                        // the
                        // tool
                        // itself
                        (existing, replacement) -> existing)) // On duplicate key, keep the
                // existing tool
                .values()
                .stream()
                .map(tool -> {
                    String toolName = tool.getToolDefinition().name();
                    MimeType mimeType = (serverProperties.getToolResponseMimeType().containsKey(toolName))
                            ? MimeType.valueOf(serverProperties.getToolResponseMimeType().get(toolName)) : null;
                    return McpToolUtils.toSyncToolSpecification(tool, mimeType);
                })
                .toList();
    }


}
