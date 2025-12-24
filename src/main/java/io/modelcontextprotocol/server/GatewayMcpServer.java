/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.DefaultMcpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public interface GatewayMcpServer {

    McpSchema.Implementation DEFAULT_SERVER_INFO = new McpSchema.Implementation("Java SDK MCP Server", "0.15.0");

    static SyncSpecification<SingleSessionSyncSpecification> sync(McpServerTransportProvider transportProvider) {
        return new SingleSessionSyncSpecification(transportProvider);
    }

    static AsyncSpecification<?> async(McpServerTransportProvider transportProvider) {
        return new SingleSessionAsyncSpecification(transportProvider);
    }

    static SyncSpecification<StreamableSyncSpecification> sync(McpStreamableServerTransportProvider transportProvider) {
        return new StreamableSyncSpecification(transportProvider);
    }

    static AsyncSpecification<?> async(McpStreamableServerTransportProvider transportProvider) {
        return new StreamableServerAsyncSpecification(transportProvider);
    }

    class SingleSessionAsyncSpecification extends AsyncSpecification<SingleSessionAsyncSpecification> {

        private final McpServerTransportProvider transportProvider;

        private SingleSessionAsyncSpecification(McpServerTransportProvider transportProvider) {
            Assert.notNull(transportProvider, "Transport provider must not be null");
            this.transportProvider = transportProvider;
        }

        @Override
        public McpAsyncServer build() {
            var features = new McpServerFeatures.Async(this.serverInfo, this.serverCapabilities, this.tools,
                    this.resources, this.resourceTemplates, this.prompts, this.completions, this.rootsChangeHandlers,
                    this.instructions);

            var jsonSchemaValidator = (this.jsonSchemaValidator != null) ? this.jsonSchemaValidator
                    : JsonSchemaValidator.getDefault();

            return new GatewayMcpAsyncServer(transportProvider,
                    jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper,
                    features, requestTimeout, uriTemplateManagerFactory, jsonSchemaValidator);
        }

    }

    class StreamableServerAsyncSpecification extends AsyncSpecification<StreamableServerAsyncSpecification> {

        private final McpStreamableServerTransportProvider transportProvider;

        public StreamableServerAsyncSpecification(McpStreamableServerTransportProvider transportProvider) {
            this.transportProvider = transportProvider;
        }

        @Override
        public McpAsyncServer build() {
            var features = new McpServerFeatures.Async(this.serverInfo, this.serverCapabilities, this.tools,
                    this.resources, this.resourceTemplates, this.prompts, this.completions, this.rootsChangeHandlers,
                    this.instructions);
            var jsonSchemaValidator = this.jsonSchemaValidator != null ? this.jsonSchemaValidator
                    : JsonSchemaValidator.getDefault();
            return new GatewayMcpAsyncServer(transportProvider,
                    jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper,
                    features, requestTimeout, uriTemplateManagerFactory, jsonSchemaValidator);
        }

    }

    abstract class AsyncSpecification<S extends AsyncSpecification<S>> {

        McpUriTemplateManagerFactory uriTemplateManagerFactory = new DefaultMcpUriTemplateManagerFactory();

        McpJsonMapper jsonMapper;

        McpSchema.Implementation serverInfo = DEFAULT_SERVER_INFO;

        McpSchema.ServerCapabilities serverCapabilities;

        JsonSchemaValidator jsonSchemaValidator;

        String instructions;

        final List<McpServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();

        final Map<String, McpServerFeatures.AsyncResourceSpecification> resources = new HashMap<>();

        final Map<String, McpServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates = new HashMap<>();

        final Map<String, McpServerFeatures.AsyncPromptSpecification> prompts = new HashMap<>();

        final Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions = new HashMap<>();

        final List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeHandlers = new ArrayList<>();

        Duration requestTimeout = Duration.ofHours(10);

        public abstract McpAsyncServer build();

        public AsyncSpecification<S> uriTemplateManagerFactory(McpUriTemplateManagerFactory uriTemplateManagerFactory) {
            Assert.notNull(uriTemplateManagerFactory, "URI template manager factory must not be null");
            this.uriTemplateManagerFactory = uriTemplateManagerFactory;
            return this;
        }

        public AsyncSpecification<S> requestTimeout(Duration requestTimeout) {
            Assert.notNull(requestTimeout, "Request timeout must not be null");
            this.requestTimeout = requestTimeout;
            return this;
        }

        public AsyncSpecification<S> serverInfo(McpSchema.Implementation serverInfo) {
            Assert.notNull(serverInfo, "Server info must not be null");
            this.serverInfo = serverInfo;
            return this;
        }

        public AsyncSpecification<S> serverInfo(String name, String version) {
            Assert.hasText(name, "Name must not be null or empty");
            Assert.hasText(version, "Version must not be null or empty");
            this.serverInfo = new McpSchema.Implementation(name, version);
            return this;
        }

        public AsyncSpecification<S> instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        public AsyncSpecification<S> capabilities(McpSchema.ServerCapabilities serverCapabilities) {
            Assert.notNull(serverCapabilities, "Server capabilities must not be null");
            this.serverCapabilities = serverCapabilities;
            return this;
        }

        public AsyncSpecification<S> tool(McpSchema.Tool tool,
                                          BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<CallToolResult>> handler) {
            Assert.notNull(tool, "Tool must not be null");
            Assert.notNull(handler, "Handler must not be null");

            this.tools.add(new McpServerFeatures.AsyncToolSpecification(tool, handler));

            return this;
        }

        public AsyncSpecification<S> tool(McpSchema.Tool tool,
                                          BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<CallToolResult>> handler,
                                          boolean isLegacyHandler) {
            Assert.notNull(tool, "Tool must not be null");
            Assert.notNull(handler, "Handler must not be null");

            this.tools.add(McpServerFeatures.AsyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler(handler)
                    .build());

            return this;
        }

        public AsyncSpecification<S> tools(List<McpServerFeatures.AsyncToolSpecification> toolSpecifications) {
            Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
            this.tools.addAll(toolSpecifications);
            return this;
        }

        public AsyncSpecification<S> tools(McpServerFeatures.AsyncToolSpecification... toolSpecifications) {
            Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
            this.tools.addAll(Arrays.asList(toolSpecifications));
            return this;
        }

        public AsyncSpecification<S> resources(
                Map<String, McpServerFeatures.AsyncResourceSpecification> resourceSpecifications) {
            Assert.notNull(resourceSpecifications, "Resource handlers map must not be null");
            this.resources.putAll(resourceSpecifications);
            return this;
        }

        public AsyncSpecification<S> resources(List<McpServerFeatures.AsyncResourceSpecification> resourceSpecifications) {
            Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
            for (McpServerFeatures.AsyncResourceSpecification resource : resourceSpecifications) {
                this.resources.put(resource.resource().uri(), resource);
            }
            return this;
        }

        public AsyncSpecification<S> resources(McpServerFeatures.AsyncResourceSpecification... resourceSpecifications) {
            Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
            for (McpServerFeatures.AsyncResourceSpecification resource : resourceSpecifications) {
                this.resources.put(resource.resource().uri(), resource);
            }
            return this;
        }

        public AsyncSpecification<S> resourceTemplates(
                List<McpServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates) {
            Assert.notNull(resourceTemplates, "Resource templates must not be null");
            for (var resourceTemplate : resourceTemplates) {
                this.resourceTemplates.put(resourceTemplate.resourceTemplate().uriTemplate(), resourceTemplate);
            }
            return this;
        }

        public AsyncSpecification<S> resourceTemplates(
                McpServerFeatures.AsyncResourceTemplateSpecification... resourceTemplates) {
            Assert.notNull(resourceTemplates, "Resource templates must not be null");
            for (McpServerFeatures.AsyncResourceTemplateSpecification resource : resourceTemplates) {
                this.resourceTemplates.put(resource.resourceTemplate().uriTemplate(), resource);
            }
            return this;
        }

        public AsyncSpecification<S> prompts(Map<String, McpServerFeatures.AsyncPromptSpecification> prompts) {
            Assert.notNull(prompts, "Prompts map must not be null");
            this.prompts.putAll(prompts);
            return this;
        }

        public AsyncSpecification<S> prompts(List<McpServerFeatures.AsyncPromptSpecification> prompts) {
            Assert.notNull(prompts, "Prompts list must not be null");
            for (McpServerFeatures.AsyncPromptSpecification prompt : prompts) {
                this.prompts.put(prompt.prompt().name(), prompt);
            }
            return this;
        }

        public AsyncSpecification<S> prompts(McpServerFeatures.AsyncPromptSpecification... prompts) {
            Assert.notNull(prompts, "Prompts list must not be null");
            for (McpServerFeatures.AsyncPromptSpecification prompt : prompts) {
                this.prompts.put(prompt.prompt().name(), prompt);
            }
            return this;
        }

        public AsyncSpecification<S> completions(List<McpServerFeatures.AsyncCompletionSpecification> completions) {
            Assert.notNull(completions, "Completions list must not be null");
            for (McpServerFeatures.AsyncCompletionSpecification completion : completions) {
                this.completions.put(completion.referenceKey(), completion);
            }
            return this;
        }

        public AsyncSpecification<S> completions(McpServerFeatures.AsyncCompletionSpecification... completions) {
            Assert.notNull(completions, "Completions list must not be null");
            for (McpServerFeatures.AsyncCompletionSpecification completion : completions) {
                this.completions.put(completion.referenceKey(), completion);
            }
            return this;
        }

        public AsyncSpecification<S> rootsChangeHandler(
                BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>> handler) {
            Assert.notNull(handler, "Consumer must not be null");
            this.rootsChangeHandlers.add(handler);
            return this;
        }

        public AsyncSpecification<S> rootsChangeHandlers(
                List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> handlers) {
            Assert.notNull(handlers, "Handlers list must not be null");
            this.rootsChangeHandlers.addAll(handlers);
            return this;
        }

        @SafeVarargs
        public final AsyncSpecification<S> rootsChangeHandlers(
                BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>... handlers) {
            Assert.notNull(handlers, "Handlers list must not be null");
            return this.rootsChangeHandlers(Arrays.asList(handlers));
        }

        public AsyncSpecification<S> jsonMapper(McpJsonMapper jsonMapper) {
            Assert.notNull(jsonMapper, "jsonMapper must not be null");
            this.jsonMapper = jsonMapper;
            return this;
        }

        public AsyncSpecification<S> jsonSchemaValidator(JsonSchemaValidator jsonSchemaValidator) {
            Assert.notNull(jsonSchemaValidator, "jsonSchemaValidator must not be null");
            this.jsonSchemaValidator = jsonSchemaValidator;
            return this;
        }

    }

    class SingleSessionSyncSpecification extends SyncSpecification<SingleSessionSyncSpecification> {

        private final McpServerTransportProvider transportProvider;

        private SingleSessionSyncSpecification(McpServerTransportProvider transportProvider) {
            Assert.notNull(transportProvider, "Transport provider must not be null");
            this.transportProvider = transportProvider;
        }

        @Override
        public McpSyncServer build() {
            McpServerFeatures.Sync syncFeatures = new McpServerFeatures.Sync(this.serverInfo, this.serverCapabilities,
                    this.tools, this.resources, this.resourceTemplates, this.prompts, this.completions,
                    this.rootsChangeHandlers, this.instructions);
            McpServerFeatures.Async asyncFeatures = McpServerFeatures.Async.fromSync(syncFeatures,
                    this.immediateExecution);
            var jsonSchemaValidator = this.jsonSchemaValidator != null ? this.jsonSchemaValidator
                    : JsonSchemaValidator.getDefault();
            var asyncServer = new GatewayMcpAsyncServer(transportProvider,
                    jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper, asyncFeatures, this.requestTimeout,
                    this.uriTemplateManagerFactory, jsonSchemaValidator);
            return new McpSyncServer(asyncServer, this.immediateExecution);
        }

    }

    class StreamableSyncSpecification extends SyncSpecification<StreamableSyncSpecification> {

        private final McpStreamableServerTransportProvider transportProvider;

        private StreamableSyncSpecification(McpStreamableServerTransportProvider transportProvider) {
            Assert.notNull(transportProvider, "Transport provider must not be null");
            this.transportProvider = transportProvider;
        }

        @Override
        public McpSyncServer build() {
            McpServerFeatures.Sync syncFeatures = new McpServerFeatures.Sync(this.serverInfo, this.serverCapabilities,
                    this.tools, this.resources, this.resourceTemplates, this.prompts, this.completions,
                    this.rootsChangeHandlers, this.instructions);
            McpServerFeatures.Async asyncFeatures = McpServerFeatures.Async.fromSync(syncFeatures,
                    this.immediateExecution);
            var jsonSchemaValidator = this.jsonSchemaValidator != null ? this.jsonSchemaValidator
                    : JsonSchemaValidator.getDefault();
            var asyncServer = new GatewayMcpAsyncServer(transportProvider,
                    jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper, asyncFeatures, this.requestTimeout,
                    this.uriTemplateManagerFactory, jsonSchemaValidator);
            return new McpSyncServer(asyncServer, this.immediateExecution);
        }

    }

    abstract class SyncSpecification<S extends SyncSpecification<S>> {

        McpUriTemplateManagerFactory uriTemplateManagerFactory = new DefaultMcpUriTemplateManagerFactory();

        McpJsonMapper jsonMapper;

        McpSchema.Implementation serverInfo = DEFAULT_SERVER_INFO;

        McpSchema.ServerCapabilities serverCapabilities;

        String instructions;

        final List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

        final Map<String, McpServerFeatures.SyncResourceSpecification> resources = new HashMap<>();

        final Map<String, McpServerFeatures.SyncResourceTemplateSpecification> resourceTemplates = new HashMap<>();

        JsonSchemaValidator jsonSchemaValidator;

        final Map<String, McpServerFeatures.SyncPromptSpecification> prompts = new HashMap<>();

        final Map<McpSchema.CompleteReference, McpServerFeatures.SyncCompletionSpecification> completions = new HashMap<>();

        final List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeHandlers = new ArrayList<>();

        Duration requestTimeout = Duration.ofSeconds(10);

        boolean immediateExecution = false;

        public abstract McpSyncServer build();

        public SyncSpecification<S> uriTemplateManagerFactory(McpUriTemplateManagerFactory uriTemplateManagerFactory) {
            Assert.notNull(uriTemplateManagerFactory, "URI template manager factory must not be null");
            this.uriTemplateManagerFactory = uriTemplateManagerFactory;
            return this;
        }

        public SyncSpecification<S> requestTimeout(Duration requestTimeout) {
            Assert.notNull(requestTimeout, "Request timeout must not be null");
            this.requestTimeout = requestTimeout;
            return this;
        }

        public SyncSpecification<S> serverInfo(McpSchema.Implementation serverInfo) {
            Assert.notNull(serverInfo, "Server info must not be null");
            this.serverInfo = serverInfo;
            return this;
        }

        public SyncSpecification<S> serverInfo(String name, String version) {
            Assert.hasText(name, "Name must not be null or empty");
            Assert.hasText(version, "Version must not be null or empty");
            this.serverInfo = new McpSchema.Implementation(name, version);
            return this;
        }

        public SyncSpecification<S> instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        public SyncSpecification<S> capabilities(McpSchema.ServerCapabilities serverCapabilities) {
            Assert.notNull(serverCapabilities, "Server capabilities must not be null");
            this.serverCapabilities = serverCapabilities;
            return this;
        }

        public SyncSpecification<S> tool(McpSchema.Tool tool,
                                         BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler) {
            Assert.notNull(tool, "Tool must not be null");
            Assert.notNull(handler, "Handler must not be null");

            this.tools.add(new McpServerFeatures.SyncToolSpecification(tool, handler));

            return this;
        }

        public SyncSpecification<S> tool(McpSchema.Tool tool,
                                         BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler,
                                         boolean isLegacyHandler) {
            Assert.notNull(tool, "Tool must not be null");
            Assert.notNull(handler, "Handler must not be null");

            this.tools.add(new McpServerFeatures.SyncToolSpecification(tool, null, handler));

            return this;
        }

        public SyncSpecification<S> tools(List<McpServerFeatures.SyncToolSpecification> toolSpecifications) {
            Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
            this.tools.addAll(toolSpecifications);
            return this;
        }

        public SyncSpecification<S> tools(McpServerFeatures.SyncToolSpecification... toolSpecifications) {
            Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
            this.tools.addAll(Arrays.asList(toolSpecifications));
            return this;
        }

        public SyncSpecification<S> resources(
                Map<String, McpServerFeatures.SyncResourceSpecification> resourceSpecifications) {
            Assert.notNull(resourceSpecifications, "Resource handlers map must not be null");
            this.resources.putAll(resourceSpecifications);
            return this;
        }

        public SyncSpecification<S> resources(List<McpServerFeatures.SyncResourceSpecification> resourceSpecifications) {
            Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
            for (McpServerFeatures.SyncResourceSpecification resource : resourceSpecifications) {
                this.resources.put(resource.resource().uri(), resource);
            }
            return this;
        }

        public SyncSpecification<S> resources(McpServerFeatures.SyncResourceSpecification... resourceSpecifications) {
            Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
            for (McpServerFeatures.SyncResourceSpecification resource : resourceSpecifications) {
                this.resources.put(resource.resource().uri(), resource);
            }
            return this;
        }

        public SyncSpecification<S> resourceTemplates(
                List<McpServerFeatures.SyncResourceTemplateSpecification> resourceTemplates) {
            Assert.notNull(resourceTemplates, "Resource templates must not be null");
            for (var resourceTemplate : resourceTemplates) {
                this.resourceTemplates.put(resourceTemplate.resourceTemplate().uriTemplate(), resourceTemplate);
            }
            return this;
        }

        public SyncSpecification<S> resourceTemplates(
                McpServerFeatures.SyncResourceTemplateSpecification... resourceTemplates) {
            Assert.notNull(resourceTemplates, "Resource templates must not be null");
            for (McpServerFeatures.SyncResourceTemplateSpecification resourceTemplate : resourceTemplates) {
                this.resourceTemplates.put(resourceTemplate.resourceTemplate().uriTemplate(), resourceTemplate);
            }
            return this;
        }

        public SyncSpecification<S> prompts(Map<String, McpServerFeatures.SyncPromptSpecification> prompts) {
            Assert.notNull(prompts, "Prompts map must not be null");
            this.prompts.putAll(prompts);
            return this;
        }

        public SyncSpecification<S> prompts(List<McpServerFeatures.SyncPromptSpecification> prompts) {
            Assert.notNull(prompts, "Prompts list must not be null");
            for (McpServerFeatures.SyncPromptSpecification prompt : prompts) {
                this.prompts.put(prompt.prompt().name(), prompt);
            }
            return this;
        }

        public SyncSpecification<S> prompts(McpServerFeatures.SyncPromptSpecification... prompts) {
            Assert.notNull(prompts, "Prompts list must not be null");
            for (McpServerFeatures.SyncPromptSpecification prompt : prompts) {
                this.prompts.put(prompt.prompt().name(), prompt);
            }
            return this;
        }

        public SyncSpecification<S> completions(List<McpServerFeatures.SyncCompletionSpecification> completions) {
            Assert.notNull(completions, "Completions list must not be null");
            for (McpServerFeatures.SyncCompletionSpecification completion : completions) {
                this.completions.put(completion.referenceKey(), completion);
            }
            return this;
        }

        public SyncSpecification<S> completions(McpServerFeatures.SyncCompletionSpecification... completions) {
            Assert.notNull(completions, "Completions list must not be null");
            for (McpServerFeatures.SyncCompletionSpecification completion : completions) {
                this.completions.put(completion.referenceKey(), completion);
            }
            return this;
        }

        public SyncSpecification<S> rootsChangeHandler(BiConsumer<McpSyncServerExchange, List<McpSchema.Root>> handler) {
            Assert.notNull(handler, "Consumer must not be null");
            this.rootsChangeHandlers.add(handler);
            return this;
        }

        public SyncSpecification<S> rootsChangeHandlers(
                List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> handlers) {
            Assert.notNull(handlers, "Handlers list must not be null");
            this.rootsChangeHandlers.addAll(handlers);
            return this;
        }

        @SafeVarargs
        public final SyncSpecification<S> rootsChangeHandlers(
                BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>... handlers) {
            Assert.notNull(handlers, "Handlers list must not be null");
            return this.rootsChangeHandlers(Arrays.asList(handlers));
        }

        public SyncSpecification<S> jsonMapper(McpJsonMapper jsonMapper) {
            Assert.notNull(jsonMapper, "jsonMapper must not be null");
            this.jsonMapper = jsonMapper;
            return this;
        }

        public SyncSpecification<S> jsonSchemaValidator(JsonSchemaValidator jsonSchemaValidator) {
            Assert.notNull(jsonSchemaValidator, "jsonSchemaValidator must not be null");
            this.jsonSchemaValidator = jsonSchemaValidator;
            return this;
        }

        public SyncSpecification<S> immediateExecution(boolean immediateExecution) {
            this.immediateExecution = immediateExecution;
            return this;
        }

    }

}
