package io.modelcontextprotocol.server;

import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.DefaultMcpStreamableServerSessionFactory;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult.CompleteCompletion;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.spec.McpSchema.ResourceReference;
import io.modelcontextprotocol.spec.McpSchema.SetLevelRequest;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.util.DefaultMcpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.Utils;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import static io.modelcontextprotocol.spec.McpError.RESOURCE_NOT_FOUND;

public class GatewayMcpAsyncServer extends McpAsyncServer {

    private static final Logger logger = LoggerFactory.getLogger(GatewayMcpAsyncServer.class);

    private final McpServerTransportProviderBase mcpTransportProvider;

    private final McpJsonMapper jsonMapper;

    private final JsonSchemaValidator jsonSchemaValidator;

    private final McpSchema.ServerCapabilities serverCapabilities;

    private final McpSchema.Implementation serverInfo;

    private final String instructions;

    private final CopyOnWriteArrayList<McpServerFeatures.AsyncToolSpecification> tools = new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<String, McpServerFeatures.AsyncResourceSpecification> resources = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, McpServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, McpServerFeatures.AsyncPromptSpecification> prompts = new ConcurrentHashMap<>();

    // FIXME: this field is deprecated and should be removed together with the
    // broadcasting loggingNotification.
    private LoggingLevel minLoggingLevel = LoggingLevel.DEBUG;

    private final ConcurrentHashMap<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions = new ConcurrentHashMap<>();

    private List<String> protocolVersions;

    private McpUriTemplateManagerFactory uriTemplateManagerFactory = new DefaultMcpUriTemplateManagerFactory();

    @Setter
    private ToolDecoratorService toolDecoratorService;

    GatewayMcpAsyncServer(McpServerTransportProvider mcpTransportProvider, McpJsonMapper jsonMapper,
                          McpServerFeatures.Async features, Duration requestTimeout,
                          McpUriTemplateManagerFactory uriTemplateManagerFactory, JsonSchemaValidator jsonSchemaValidator) {
        super(mcpTransportProvider, jsonMapper, features, requestTimeout, uriTemplateManagerFactory, jsonSchemaValidator);
        this.mcpTransportProvider = mcpTransportProvider;
        this.jsonMapper = jsonMapper;
        this.serverInfo = features.serverInfo();
        this.serverCapabilities = features.serverCapabilities().mutate().logging().build();
        this.instructions = features.instructions();
        this.tools.addAll(withStructuredOutputHandling(jsonSchemaValidator, features.tools()));
        this.resources.putAll(features.resources());
        this.resourceTemplates.putAll(features.resourceTemplates());
        this.prompts.putAll(features.prompts());
        this.completions.putAll(features.completions());
        this.uriTemplateManagerFactory = uriTemplateManagerFactory;
        this.jsonSchemaValidator = jsonSchemaValidator;

        Map<String, McpRequestHandler<?>> requestHandlers = prepareRequestHandlers();
        Map<String, McpNotificationHandler> notificationHandlers = prepareNotificationHandlers(features);

        this.protocolVersions = mcpTransportProvider.protocolVersions();

        mcpTransportProvider.setSessionFactory(transport -> new McpServerSession(UUID.randomUUID().toString(),
                requestTimeout, transport, this::asyncInitializeRequestHandler, requestHandlers, notificationHandlers));
    }

    GatewayMcpAsyncServer(McpStreamableServerTransportProvider mcpTransportProvider, McpJsonMapper jsonMapper,
                          McpServerFeatures.Async features, Duration requestTimeout,
                          McpUriTemplateManagerFactory uriTemplateManagerFactory, JsonSchemaValidator jsonSchemaValidator) {
        super(mcpTransportProvider, jsonMapper, features, requestTimeout, uriTemplateManagerFactory, jsonSchemaValidator);
        this.mcpTransportProvider = mcpTransportProvider;
        this.jsonMapper = jsonMapper;
        this.serverInfo = features.serverInfo();
        this.serverCapabilities = features.serverCapabilities().mutate().logging().build();
        this.instructions = features.instructions();
        this.tools.addAll(withStructuredOutputHandling(jsonSchemaValidator, features.tools()));
        this.resources.putAll(features.resources());
        this.resourceTemplates.putAll(features.resourceTemplates());
        this.prompts.putAll(features.prompts());
        this.completions.putAll(features.completions());
        this.uriTemplateManagerFactory = uriTemplateManagerFactory;
        this.jsonSchemaValidator = jsonSchemaValidator;

        Map<String, McpRequestHandler<?>> requestHandlers = prepareRequestHandlers();
        Map<String, McpNotificationHandler> notificationHandlers = prepareNotificationHandlers(features);

        this.protocolVersions = mcpTransportProvider.protocolVersions();

        mcpTransportProvider.setSessionFactory(new DefaultMcpStreamableServerSessionFactory(requestTimeout,
                this::asyncInitializeRequestHandler, requestHandlers, notificationHandlers));
    }

    private Map<String, McpNotificationHandler> prepareNotificationHandlers(McpServerFeatures.Async features) {
        Map<String, McpNotificationHandler> notificationHandlers = new HashMap<>();

        notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_INITIALIZED, (exchange, params) -> Mono.empty());

        List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers = features
                .rootsChangeConsumers();

        if (Utils.isEmpty(rootsChangeConsumers)) {
            rootsChangeConsumers = List.of((exchange, roots) -> Mono.fromRunnable(() -> logger
                    .warn("Roots list changed notification, but no consumers provided. Roots list changed: {}", roots)));
        }

        notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_ROOTS_LIST_CHANGED,
                asyncRootsListChangedNotificationHandler(rootsChangeConsumers));
        return notificationHandlers;
    }

    private Map<String, McpRequestHandler<?>> prepareRequestHandlers() {
        Map<String, McpRequestHandler<?>> requestHandlers = new HashMap<>();

        // Ping MUST respond with an empty data, but not NULL response.
        requestHandlers.put(McpSchema.METHOD_PING, (exchange, params) -> Mono.just(Map.of()));

        if (this.serverCapabilities.tools() != null) {
            requestHandlers.put(McpSchema.METHOD_TOOLS_LIST, toolsListRequestHandler());
            requestHandlers.put(McpSchema.METHOD_TOOLS_CALL, toolsCallRequestHandler());
        }

        if (this.serverCapabilities.resources() != null) {
            requestHandlers.put(McpSchema.METHOD_RESOURCES_LIST, resourcesListRequestHandler());
            requestHandlers.put(McpSchema.METHOD_RESOURCES_READ, resourcesReadRequestHandler());
            requestHandlers.put(McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, resourceTemplateListRequestHandler());
        }

        if (this.serverCapabilities.prompts() != null) {
            requestHandlers.put(McpSchema.METHOD_PROMPT_LIST, promptsListRequestHandler());
            requestHandlers.put(McpSchema.METHOD_PROMPT_GET, promptsGetRequestHandler());
        }

        if (this.serverCapabilities.logging() != null) {
            requestHandlers.put(McpSchema.METHOD_LOGGING_SET_LEVEL, setLoggerRequestHandler());
        }

        if (this.serverCapabilities.completions() != null) {
            requestHandlers.put(McpSchema.METHOD_COMPLETION_COMPLETE, completionCompleteRequestHandler());
        }
        return requestHandlers;
    }

    private Mono<McpSchema.InitializeResult> asyncInitializeRequestHandler(McpSchema.InitializeRequest initializeRequest) {
        return Mono.defer(() -> {
            logger.info("Client initialize request - Protocol: {}, Capabilities: {}, Info: {}",
                    initializeRequest.protocolVersion(), initializeRequest.capabilities(), initializeRequest.clientInfo());

            String serverProtocolVersion = this.protocolVersions.get(this.protocolVersions.size() - 1);

            if (this.protocolVersions.contains(initializeRequest.protocolVersion())) {
                serverProtocolVersion = initializeRequest.protocolVersion();
            }
            else {
                logger.warn(
                        "Client requested unsupported protocol version: {}, so the server will suggest the {} version instead",
                        initializeRequest.protocolVersion(), serverProtocolVersion);
            }

            return Mono.just(new McpSchema.InitializeResult(serverProtocolVersion, this.serverCapabilities,
                    this.serverInfo, this.instructions));
        });
    }

    public McpSchema.ServerCapabilities getServerCapabilities() {
        return this.serverCapabilities;
    }

    public McpSchema.Implementation getServerInfo() {
        return this.serverInfo;
    }

    public Mono<Void> closeGracefully() {
        return this.mcpTransportProvider.closeGracefully();
    }

    public void close() {
        this.mcpTransportProvider.close();
    }

    private McpNotificationHandler asyncRootsListChangedNotificationHandler(
            List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers) {
        return (exchange, params) -> exchange.listRoots()
                .flatMap(listRootsResult -> Flux.fromIterable(rootsChangeConsumers)
                        .flatMap(consumer -> consumer.apply(exchange, listRootsResult.roots()))
                        .onErrorResume(error -> {
                            logger.error("Error handling roots list change notification", error);
                            return Mono.empty();
                        })
                        .then());
    }

    public Mono<Void> addTool(McpServerFeatures.AsyncToolSpecification toolSpecification) {
        if (toolSpecification == null) {
            return Mono.error(new McpError("Tool specification must not be null"));
        }
        if (toolSpecification.tool() == null) {
            return Mono.error(new McpError("Tool must not be null"));
        }
        if (toolSpecification.callHandler() == null) {
            return Mono.error(new McpError("Tool call handler must not be null"));
        }
        if (this.serverCapabilities.tools() == null) {
            return Mono.error(new McpError("Server must be configured with tool capabilities"));
        }

        return Mono.defer(() -> {
            if (this.tools.stream().anyMatch(th -> th.tool().name().equals(toolSpecification.tool().name()))) {
                return Mono.error(new McpError("Tool with name '" + toolSpecification.tool().name() + "' already exists"));
            }

            this.tools.add(withStructuredOutputHandling(jsonSchemaValidator, toolSpecification));
            logger.debug("Added tool handler: {}", toolSpecification.tool().name());

            if (this.serverCapabilities.tools().listChanged()) {
                return notifyToolsListChanged();
            }
            return Mono.empty();
        });
    }

    public Flux<McpSchema.Tool> listTools() {
        return Flux.fromIterable(this.tools).map(McpServerFeatures.AsyncToolSpecification::tool);
    }

    public Mono<Void> removeTool(String toolName) {
        if (toolName == null) {
            return Mono.error(new McpError("Tool name must not be null"));
        }
        if (this.serverCapabilities.tools() == null) {
            return Mono.error(new McpError("Server must be configured with tool capabilities"));
        }

        return Mono.defer(() -> {
            boolean removed = this.tools
                    .removeIf(toolSpecification -> toolSpecification.tool().name().equals(toolName));
            if (removed) {
                logger.debug("Removed tool handler: {}", toolName);
                if (this.serverCapabilities.tools().listChanged()) {
                    return notifyToolsListChanged();
                }
                return Mono.empty();
            }
            return Mono.error(new McpError("Tool with name '" + toolName + "' not found"));
        });
    }

    public Mono<Void> notifyToolsListChanged() {
        return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, null);
    }

    private McpRequestHandler<McpSchema.ListToolsResult> toolsListRequestHandler() {
        return (exchange, params) -> {
            List<McpSchema.Tool> tools = this.tools.stream()
                    .filter(Predicate.not(s -> toolDecoratorService != null && toolDecoratorService.isOnlyAddable(s)))
                    .map(McpServerFeatures.AsyncToolSpecification::tool)
                    .toList();

            return Mono.just(new McpSchema.ListToolsResult(tools, null));
        };
    }

    private McpRequestHandler<McpSchema.CallToolResult> toolsCallRequestHandler() {
        return (exchange, params) -> {
            McpSchema.CallToolRequest callToolRequest = jsonMapper.convertValue(params,
                    new TypeRef<McpSchema.CallToolRequest>() {
                    });

            Optional<McpServerFeatures.AsyncToolSpecification> toolSpecification = this.tools.stream()
                    .filter(tr -> callToolRequest.name().equals(tr.tool().name()))
                    .findAny();

            if (toolSpecification.isEmpty()) {
                return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
                        .message("Unknown tool: invalid_tool_name")
                        .data("Tool not found: " + callToolRequest.name())
                        .build());
            }

            return toolSpecification.get().callHandler().apply(exchange, callToolRequest);
        };
    }

    public Mono<Void> addResource(McpServerFeatures.AsyncResourceSpecification resourceSpecification) {
        if (resourceSpecification == null || resourceSpecification.resource() == null) {
            return Mono.error(new IllegalArgumentException("Resource must not be null"));
        }

        if (this.serverCapabilities.resources() == null) {
            return Mono.error(new IllegalStateException(
                    "Server must be configured with resource capabilities to allow adding resources"));
        }

        return Mono.defer(() -> {
            var previous = this.resources.put(resourceSpecification.resource().uri(), resourceSpecification);
            if (previous != null) {
                logger.warn("Replace existing Resource with URI '{}'", resourceSpecification.resource().uri());
            }
            else {
                logger.debug("Added resource handler: {}", resourceSpecification.resource().uri());
            }
            if (this.serverCapabilities.resources().listChanged()) {
                return notifyResourcesListChanged();
            }
            return Mono.empty();
        });
    }

    public Flux<McpSchema.Resource> listResources() {
        return Flux.fromIterable(this.resources.values()).map(McpServerFeatures.AsyncResourceSpecification::resource);
    }

    public Mono<Void> removeResource(String resourceUri) {
        if (resourceUri == null) {
            return Mono.error(new IllegalArgumentException("Resource URI must not be null"));
        }
        if (this.serverCapabilities.resources() == null) {
            return Mono.error(new IllegalStateException(
                    "Server must be configured with resource capabilities to allow removing resources"));
        }

        return Mono.defer(() -> {
            McpServerFeatures.AsyncResourceSpecification removed = this.resources.remove(resourceUri);
            if (removed != null) {
                logger.debug("Removed resource handler: {}", resourceUri);
                if (this.serverCapabilities.resources().listChanged()) {
                    return notifyResourcesListChanged();
                }
                return Mono.empty();
            }
            else {
                logger.warn("Ignore as a Resource with URI '{}' not found", resourceUri);
            }
            return Mono.empty();
        });
    }

    public Mono<Void> addResourceTemplate(
            McpServerFeatures.AsyncResourceTemplateSpecification resourceTemplateSpecification) {

        if (this.serverCapabilities.resources() == null) {
            return Mono.error(new IllegalStateException(
                    "Server must be configured with resource capabilities to allow adding resource templates"));
        }

        return Mono.defer(() -> {
            var previous = this.resourceTemplates.put(resourceTemplateSpecification.resourceTemplate().uriTemplate(),
                    resourceTemplateSpecification);
            if (previous != null) {
                logger.warn("Replace existing Resource Template with URI '{}'",
                        resourceTemplateSpecification.resourceTemplate().uriTemplate());
            }
            else {
                logger.debug("Added resource template handler: {}",
                        resourceTemplateSpecification.resourceTemplate().uriTemplate());
            }
            if (this.serverCapabilities.resources().listChanged()) {
                return notifyResourcesListChanged();
            }
            return Mono.empty();
        });
    }

    public Flux<McpSchema.ResourceTemplate> listResourceTemplates() {
        return Flux.fromIterable(this.resourceTemplates.values())
                .map(McpServerFeatures.AsyncResourceTemplateSpecification::resourceTemplate);
    }

    public Mono<Void> removeResourceTemplate(String uriTemplate) {

        if (this.serverCapabilities.resources() == null) {
            return Mono.error(new IllegalStateException(
                    "Server must be configured with resource capabilities to allow removing resource templates"));
        }

        return Mono.defer(() -> {
            McpServerFeatures.AsyncResourceTemplateSpecification removed = this.resourceTemplates.remove(uriTemplate);
            if (removed != null) {
                logger.debug("Removed resource template: {}", uriTemplate);
            }
            else {
                logger.warn("Ignore as a Resource Template with URI '{}' not found", uriTemplate);
            }
            return Mono.empty();
        });
    }

    public Mono<Void> notifyResourcesListChanged() {
        return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED, null);
    }

    public Mono<Void> notifyResourcesUpdated(McpSchema.ResourcesUpdatedNotification resourcesUpdatedNotification) {
        return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED,
                resourcesUpdatedNotification);
    }

    private McpRequestHandler<McpSchema.ListResourcesResult> resourcesListRequestHandler() {
        return (exchange, params) -> {
            var resourceList = this.resources.values()
                    .stream()
                    .map(McpServerFeatures.AsyncResourceSpecification::resource)
                    .toList();
            return Mono.just(new McpSchema.ListResourcesResult(resourceList, null));
        };
    }

    private McpRequestHandler<McpSchema.ListResourceTemplatesResult> resourceTemplateListRequestHandler() {
        return (exchange, params) -> {
            var resourceList = this.resourceTemplates.values()
                    .stream()
                    .map(McpServerFeatures.AsyncResourceTemplateSpecification::resourceTemplate)
                    .toList();
            return Mono.just(new McpSchema.ListResourceTemplatesResult(resourceList, null));
        };
    }

    private McpRequestHandler<McpSchema.ReadResourceResult> resourcesReadRequestHandler() {
        return (ex, params) -> {
            McpSchema.ReadResourceRequest resourceRequest = jsonMapper.convertValue(params, new TypeRef<>() {
            });

            var resourceUri = resourceRequest.uri();

            return this.findResourceSpecification(resourceUri)
                    .map(spec -> spec.readHandler().apply(ex, resourceRequest))
                    .orElseGet(() -> this.findResourceTemplateSpecification(resourceUri)
                            .map(spec -> spec.readHandler().apply(ex, resourceRequest))
                            .orElseGet(() -> Mono.error(RESOURCE_NOT_FOUND.apply(resourceUri))));
        };
    }

    private Optional<McpServerFeatures.AsyncResourceSpecification> findResourceSpecification(String uri) {
        return this.resources.values()
                .stream()
                .filter(spec -> this.uriTemplateManagerFactory.create(spec.resource().uri()).matches(uri))
                .findFirst();
    }

    private Optional<McpServerFeatures.AsyncResourceTemplateSpecification> findResourceTemplateSpecification(
            String uri) {
        return this.resourceTemplates.values()
                .stream()
                .filter(spec -> this.uriTemplateManagerFactory.create(spec.resourceTemplate().uriTemplate()).matches(uri))
                .findFirst();
    }

    public Mono<Void> addPrompt(McpServerFeatures.AsyncPromptSpecification promptSpecification) {
        if (promptSpecification == null) {
            return Mono.error(new IllegalArgumentException("Prompt specification must not be null"));
        }
        if (this.serverCapabilities.prompts() == null) {
            return Mono.error(new IllegalStateException("Server must be configured with prompt capabilities"));
        }

        return Mono.defer(() -> {
            var previous = this.prompts.put(promptSpecification.prompt().name(), promptSpecification);
            if (previous != null) {
                logger.warn("Replace existing Prompt with name '{}'", promptSpecification.prompt().name());
            }
            else {
                logger.debug("Added prompt handler: {}", promptSpecification.prompt().name());
            }
            if (this.serverCapabilities.prompts().listChanged()) {
                return this.notifyPromptsListChanged();
            }

            return Mono.empty();
        });
    }

    public Flux<McpSchema.Prompt> listPrompts() {
        return Flux.fromIterable(this.prompts.values()).map(McpServerFeatures.AsyncPromptSpecification::prompt);
    }

    public Mono<Void> removePrompt(String promptName) {
        if (promptName == null) {
            return Mono.error(new IllegalArgumentException("Prompt name must not be null"));
        }
        if (this.serverCapabilities.prompts() == null) {
            return Mono.error(new IllegalStateException("Server must be configured with prompt capabilities"));
        }

        return Mono.defer(() -> {
            McpServerFeatures.AsyncPromptSpecification removed = this.prompts.remove(promptName);

            if (removed != null) {
                logger.debug("Removed prompt handler: {}", promptName);
                if (this.serverCapabilities.prompts().listChanged()) {
                    return this.notifyPromptsListChanged();
                }
                return Mono.empty();
            }
            else {
                logger.warn("Ignore as a Prompt with name '{}' not found", promptName);
            }
            return Mono.empty();
        });
    }

    public Mono<Void> notifyPromptsListChanged() {
        return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, null);
    }

    private McpRequestHandler<McpSchema.ListPromptsResult> promptsListRequestHandler() {
        return (exchange, params) -> {
            var promptList = this.prompts.values()
                    .stream()
                    .map(McpServerFeatures.AsyncPromptSpecification::prompt)
                    .toList();

            return Mono.just(new McpSchema.ListPromptsResult(promptList, null));
        };
    }

    private McpRequestHandler<McpSchema.GetPromptResult> promptsGetRequestHandler() {
        return (exchange, params) -> {
            McpSchema.GetPromptRequest promptRequest = jsonMapper.convertValue(params,
                    new TypeRef<McpSchema.GetPromptRequest>() {
                    });

            McpServerFeatures.AsyncPromptSpecification specification = this.prompts.get(promptRequest.name());

            if (specification == null) {
                return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
                        .message("Invalid prompt name")
                        .data("Prompt not found: " + promptRequest.name())
                        .build());
            }

            return Mono.defer(() -> specification.promptHandler().apply(exchange, promptRequest));
        };
    }

    @Deprecated
    public Mono<Void> loggingNotification(LoggingMessageNotification loggingMessageNotification) {

        if (loggingMessageNotification == null) {
            return Mono.error(new McpError("Logging message must not be null"));
        }

        if (loggingMessageNotification.level().level() < minLoggingLevel.level()) {
            return Mono.empty();
        }

        return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_MESSAGE,
                loggingMessageNotification);
    }

    private McpRequestHandler<Object> setLoggerRequestHandler() {
        return (exchange, params) -> Mono.defer(() -> {

            SetLevelRequest newMinLoggingLevel = jsonMapper.convertValue(params, new TypeRef<SetLevelRequest>() {
            });

            exchange.setMinLoggingLevel(newMinLoggingLevel.level());

            // FIXME: this field is deprecated and should be removed together
            // with the broadcasting loggingNotification.
            this.minLoggingLevel = newMinLoggingLevel.level();

            return Mono.just(Map.of());
        });
    }

    private static final Mono<McpSchema.CompleteResult> EMPTY_COMPLETION_RESULT = Mono
            .just(new McpSchema.CompleteResult(new CompleteCompletion(List.of(), 0, false)));

    private McpRequestHandler<McpSchema.CompleteResult> completionCompleteRequestHandler() {
        return (exchange, params) -> {

            McpSchema.CompleteRequest request = parseCompletionParams(params);

            if (request.ref() == null) {
                return Mono.error(
                        McpError.builder(ErrorCodes.INVALID_PARAMS).message("Completion ref must not be null").build());
            }

            if (request.ref().type() == null) {
                return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
                        .message("Completion ref type must not be null")
                        .build());
            }

            String type = request.ref().type();

            String argumentName = request.argument().name();

            if (type.equals(PromptReference.TYPE)
                    && request.ref() instanceof McpSchema.PromptReference promptReference) {

                McpServerFeatures.AsyncPromptSpecification promptSpec = this.prompts.get(promptReference.name());
                if (promptSpec == null) {
                    return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
                            .message("Prompt not found: " + promptReference.name())
                            .build());
                }
                if (promptSpec.prompt()
                        .arguments()
                        .stream()
                        .filter(arg -> arg.name().equals(argumentName))
                        .findFirst()
                        .isEmpty()) {

                    logger.warn("Argument not found: {} in prompt: {}", argumentName, promptReference.name());

                    return EMPTY_COMPLETION_RESULT;
                }
            }

            if (type.equals(ResourceReference.TYPE)
                    && request.ref() instanceof McpSchema.ResourceReference resourceReference) {

                var uriTemplateManager = uriTemplateManagerFactory.create(resourceReference.uri());

                if (!uriTemplateManager.isUriTemplate(resourceReference.uri())) {
                    return EMPTY_COMPLETION_RESULT;
                }

                McpServerFeatures.AsyncResourceSpecification resourceSpec = this
                        .findResourceSpecification(resourceReference.uri())
                        .orElse(null);

                if (resourceSpec != null) {
                    if (!uriTemplateManagerFactory.create(resourceSpec.resource().uri())
                            .getVariableNames()
                            .contains(argumentName)) {

                        return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
                                .message("Argument not found: " + argumentName + " in resource: " + resourceReference.uri())
                                .build());
                    }
                }
                else {
                    var templateSpec = this.findResourceTemplateSpecification(resourceReference.uri()).orElse(null);
                    if (templateSpec != null) {

                        if (!uriTemplateManagerFactory.create(templateSpec.resourceTemplate().uriTemplate())
                                .getVariableNames()
                                .contains(argumentName)) {

                            return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
                                    .message("Argument not found: " + argumentName + " in resource template: "
                                            + resourceReference.uri())
                                    .build());
                        }
                    }
                    else {
                        return Mono.error(RESOURCE_NOT_FOUND.apply(resourceReference.uri()));
                    }
                }
            }

            McpServerFeatures.AsyncCompletionSpecification specification = this.completions.get(request.ref());

            if (specification == null) {
                return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
                        .message("AsyncCompletionSpecification not found: " + request.ref())
                        .build());
            }

            return Mono.defer(() -> specification.completionHandler().apply(exchange, request));
        };
    }

    @SuppressWarnings("unchecked")
    private McpSchema.CompleteRequest parseCompletionParams(Object object) {
        Map<String, Object> params = (Map<String, Object>) object;
        Map<String, Object> refMap = (Map<String, Object>) params.get("ref");
        Map<String, Object> argMap = (Map<String, Object>) params.get("argument");

        String refType = (String) refMap.get("type");

        McpSchema.CompleteReference ref = switch (refType) {
            case PromptReference.TYPE -> new McpSchema.PromptReference(refType, (String) refMap.get("name"));
            case ResourceReference.TYPE -> new McpSchema.ResourceReference(refType, (String) refMap.get("uri"));
            default -> throw new IllegalArgumentException("Invalid ref type: " + refType);
        };

        String argName = (String) argMap.get("name");
        String argValue = (String) argMap.get("value");
        McpSchema.CompleteRequest.CompleteArgument argument = new McpSchema.CompleteRequest.CompleteArgument(argName,
                argValue);

        return new McpSchema.CompleteRequest(ref, argument);
    }

    void setProtocolVersions(List<String> protocolVersions) {
        this.protocolVersions = protocolVersions;
    }

    private static List<McpServerFeatures.AsyncToolSpecification> withStructuredOutputHandling(
            JsonSchemaValidator jsonSchemaValidator, List<McpServerFeatures.AsyncToolSpecification> tools) {
        if (jsonSchemaValidator == null) {
            return tools;
        }
        return tools.stream()
                .map(spec -> withStructuredOutputHandling(jsonSchemaValidator, spec))
                .toList();
    }

    private static McpServerFeatures.AsyncToolSpecification withStructuredOutputHandling(
            JsonSchemaValidator jsonSchemaValidator, McpServerFeatures.AsyncToolSpecification toolSpecification) {

        if (jsonSchemaValidator == null || toolSpecification.tool().outputSchema() == null) {
            return toolSpecification;
        }

        var tool = toolSpecification.tool();
        var outputSchema = tool.outputSchema();

        var callHandler = toolSpecification.callHandler();
        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> callHandler.apply(exchange, request)
                        .map(result -> {
                            jsonSchemaValidator.validate(outputSchema, result);
                            return result;
                        }))
                .build();
    }
}
