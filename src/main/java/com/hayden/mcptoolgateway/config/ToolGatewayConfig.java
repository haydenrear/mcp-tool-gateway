package com.hayden.mcptoolgateway.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.mcptoolgateway.security.IdentityResolver;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.utilitymodule.MapFunctions;
import com.hayden.utilitymodule.stream.StreamUtil;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.autoconfigure.configurer.McpSyncClientConfigurer;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpSseClientProperties;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.mcp.customizer.McpSyncClientCustomizer;
import org.springframework.ai.mcp.server.autoconfigure.McpServerProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider.DEFAULT_SSE_ENDPOINT;

@Slf4j
@Configuration
@EnableConfigurationProperties(McpServerProperties.class)
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

    @Bean
    @ConditionalOnMissingBean
    public McpSchema.ServerCapabilities.Builder capabilitiesBuilder() {
        return McpSchema.ServerCapabilities.builder();
    }

    @SneakyThrows
    @Bean("mcpSyncServer")
    @Primary
    public McpSyncServer mcpSyncServer(McpServerTransportProvider transportProvider,
                                                  McpSchema.ServerCapabilities.Builder capabilitiesBuilder,
                                                  McpServerProperties serverProperties,
                                                  ObjectProvider<List<McpServerFeatures.SyncToolSpecification>> tools,
                                                  ObjectProvider<List<McpServerFeatures.SyncResourceSpecification>> resources,
                                                  ObjectProvider<List<McpServerFeatures.SyncPromptSpecification>> prompts,
                                                  ObjectProvider<List<McpServerFeatures.SyncCompletionSpecification>> completions,
                                                  ObjectProvider<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeConsumers,
                                                  List<ToolCallbackProvider> toolCallbackProvider,
                                                  @Lazy ToolDecoratorService toolDecoratorService) {
        McpSchema.Implementation serverInfo = new McpSchema.Implementation(serverProperties.getName(),
                serverProperties.getVersion());

        // Create the server with both tool and resource capabilities
        GatewayMcpServer.SyncSpecification serverBuilder = GatewayMcpServer.sync(transportProvider).serverInfo(serverInfo);

        // Tools
        if (serverProperties.getCapabilities().isTool()) {
            log.info("Enable tools capabilities, notification: " + serverProperties.isToolChangeNotification());
            capabilitiesBuilder.tools(serverProperties.isToolChangeNotification());

            List<McpServerFeatures.SyncToolSpecification> toolSpecifications = new ArrayList<>(
                    tools.stream().flatMap(List::stream).toList());

            List<ToolCallback> providerToolCallbacks = toolCallbackProvider.stream()
                    .map(pr -> List.of(pr.getToolCallbacks()))
                    .flatMap(List::stream)
                    .filter(fc -> fc instanceof ToolCallback)
                    .map(fc -> (ToolCallback) fc)
                    .toList();

            toolSpecifications.addAll(this.toSyncToolSpecifications(providerToolCallbacks, serverProperties));

            if (!CollectionUtils.isEmpty(toolSpecifications)) {
                serverBuilder.tools(toolSpecifications);
                log.info("Registered tools: " + toolSpecifications.size());
            }
        }

        // Resources
        if (serverProperties.getCapabilities().isResource()) {
            log.info(
                    "Enable resources capabilities, notification: " + serverProperties.isResourceChangeNotification());
            capabilitiesBuilder.resources(false, serverProperties.isResourceChangeNotification());

            List<McpServerFeatures.SyncResourceSpecification> resourceSpecifications = resources.stream().flatMap(List::stream).toList();
            if (!CollectionUtils.isEmpty(resourceSpecifications)) {
                serverBuilder.resources(resourceSpecifications);
                log.info("Registered resources: " + resourceSpecifications.size());
            }
        }

        // Prompts
        if (serverProperties.getCapabilities().isPrompt()) {
            log.info("Enable prompts capabilities, notification: " + serverProperties.isPromptChangeNotification());
            capabilitiesBuilder.prompts(serverProperties.isPromptChangeNotification());

            List<McpServerFeatures.SyncPromptSpecification> promptSpecifications = prompts.stream().flatMap(List::stream).toList();
            if (!CollectionUtils.isEmpty(promptSpecifications)) {
                serverBuilder.prompts(promptSpecifications);
                log.info("Registered prompts: " + promptSpecifications.size());
            }
        }

        // Completions
        if (serverProperties.getCapabilities().isCompletion()) {
            log.info("Enable completions capabilities");
            capabilitiesBuilder.completions();

            List<McpServerFeatures.SyncCompletionSpecification> completionSpecifications = completions.stream()
                    .flatMap(List::stream)
                    .toList();
            if (!CollectionUtils.isEmpty(completionSpecifications)) {
                serverBuilder.completions(completionSpecifications);
                log.info("Registered completions: " + completionSpecifications.size());
            }
        }

        rootsChangeConsumers.ifAvailable(consumer -> {
            serverBuilder.rootsChangeHandler((exchange, roots) -> consumer.accept(exchange, roots));
            log.info("Registered roots change consumer");
        });

        serverBuilder.capabilities(capabilitiesBuilder.build());

        serverBuilder.instructions(serverProperties.getInstructions());

        serverBuilder.requestTimeout(serverProperties.getRequestTimeout());

        var builtServer = serverBuilder.build();
        if (builtServer.getAsyncServer() instanceof GatewayMcpAsyncServer g) {
            g.setToolDecoratorService(toolDecoratorService);
        }

        return builtServer;
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
