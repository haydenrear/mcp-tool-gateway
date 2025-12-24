package io.modelcontextprotocol.client.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.security.IdentityResolver;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.transport.customizer.DelegatingMcpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public class AuthAwareHttpStreamableClientTransport implements io.modelcontextprotocol.spec.McpClientTransport {

    private static final String DEFAULT_MCP_ENDPOINT = "/mcp";
    private static final String AUTH_CONTEXT_KEY = "authorization";

    private final HttpClientStreamableHttpTransport delegate;
    private final McpJsonMapper jsonMapper;
    private final ObjectMapper objectMapper;
    private final IdentityResolver resolver;
    private final ToolGatewayConfigProperties.DecoratedMcpServer configProperties;
    private final String baseUri;
    private final String endpoint;

    private AuthAwareHttpStreamableClientTransport(HttpClientStreamableHttpTransport delegate, McpJsonMapper jsonMapper,
                                                   ObjectMapper objectMapper, IdentityResolver resolver,
                                                   ToolGatewayConfigProperties.DecoratedMcpServer configProperties,
                                                   String baseUri, String endpoint) {
        this.delegate = delegate;
        this.jsonMapper = jsonMapper;
        this.objectMapper = objectMapper;
        this.resolver = resolver;
        this.configProperties = configProperties;
        this.baseUri = baseUri;
        this.endpoint = endpoint;
    }

    public static Builder authAwareBuilder(String baseUri) {
        return new Builder(baseUri);
    }

    public String getBaseUri() {
        return baseUri;
    }

    public String getEndpoint() {
        return endpoint;
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return delegate.connect(handler);
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        delegate.setExceptionHandler(handler);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        StrippedMessage stripped = stripBearer(message);
        Mono<Void> send = delegate.sendMessage(stripped.message());
        if (StringUtils.isNotBlank(stripped.bearer())) {
            return send.contextWrite(ctx -> {
                if (ctx.hasKey(McpTransportContext.KEY)) {
                    return ctx;
                }
                return ctx.put(McpTransportContext.KEY, McpTransportContext.create(Map.of(AUTH_CONTEXT_KEY, stripped.bearer())));
            });
        }
        return send;
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return jsonMapper.convertValue(data, typeRef);
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    private StrippedMessage stripBearer(McpSchema.JSONRPCMessage message) {
        if (message instanceof McpSchema.JSONRPCRequest request) {
            try {
                IdentityResolver.BodyAndBearer stripped = IdentityResolver.serializeStrippingBearer(request, objectMapper);
                McpSchema.JSONRPCMessage sanitized = McpSchema.deserializeJsonRpcMessage(jsonMapper, stripped.json());
                return new StrippedMessage(sanitized, stripped.bearer());
            }
            catch (IOException e) {
                throw new RuntimeException("Failed to serialize JSON-RPC message", e);
            }
        }
        return new StrippedMessage(message, null);
    }

    private record StrippedMessage(McpSchema.JSONRPCMessage message, String bearer) { }

    private static class AuthAwareRequestCustomizer implements McpAsyncHttpClientRequestCustomizer {

        private final IdentityResolver resolver;
        private final ToolGatewayConfigProperties.DecoratedMcpServer configProperties;

        private AuthAwareRequestCustomizer(IdentityResolver resolver,
                                           ToolGatewayConfigProperties.DecoratedMcpServer configProperties) {
            this.resolver = resolver;
            this.configProperties = configProperties;
        }

        @Override
        public org.reactivestreams.Publisher<HttpRequest.Builder> customize(HttpRequest.Builder builder, String method,
                                                                            URI uri, String body,
                                                                            McpTransportContext transportContext) {
            Object authValue = transportContext != null ? transportContext.get(AUTH_CONTEXT_KEY) : null;
            if (authValue instanceof String bearer && StringUtils.isNotBlank(bearer)) {
                builder.header("Authorization", bearer);
                return Mono.just(builder);
            }
            if (resolver == null || configProperties == null) {
                return Mono.just(builder);
            }
            return resolver.s2sIdentity(configProperties)
                    .map(token -> {
                        if (StringUtils.isNotBlank(token)) {
                            builder.header("Authorization", token);
                        }
                        return builder;
                    })
                    .defaultIfEmpty(builder);
        }
    }

    public static class Builder {

        private final String baseUri;

        private String endpoint = DEFAULT_MCP_ENDPOINT;
        private HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1);
        private HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .header("Content-Type", "application/json");
        private ObjectMapper objectMapper = new ObjectMapper();
        private IdentityResolver authResolver;
        private ToolGatewayConfigProperties.DecoratedMcpServer configProperties;
        private McpAsyncHttpClientRequestCustomizer requestCustomizer = McpAsyncHttpClientRequestCustomizer.NOOP;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private boolean resumableStreams = true;
        private boolean openConnectionOnStartup = false;

        private Builder(String baseUri) {
            Assert.hasText(baseUri, "baseUri must not be empty");
            this.baseUri = baseUri;
        }

        public Builder endpoint(String endpoint) {
            Assert.hasText(endpoint, "endpoint must not be empty");
            this.endpoint = endpoint;
            return this;
        }

        public Builder clientBuilder(HttpClient.Builder clientBuilder) {
            Assert.notNull(clientBuilder, "clientBuilder must not be null");
            this.clientBuilder = clientBuilder;
            return this;
        }

        public Builder customizeClient(final Consumer<HttpClient.Builder> clientCustomizer) {
            Assert.notNull(clientCustomizer, "clientCustomizer must not be null");
            clientCustomizer.accept(clientBuilder);
            return this;
        }

        public Builder requestBuilder(HttpRequest.Builder requestBuilder) {
            Assert.notNull(requestBuilder, "requestBuilder must not be null");
            this.requestBuilder = requestBuilder;
            return this;
        }

        public Builder customizeRequest(final Consumer<HttpRequest.Builder> requestCustomizer) {
            Assert.notNull(requestCustomizer, "requestCustomizer must not be null");
            requestCustomizer.accept(requestBuilder);
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            Assert.notNull(objectMapper, "objectMapper must not be null");
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder authResolver(IdentityResolver authResolver) {
            Assert.notNull(authResolver, "authResolver must not be null");
            this.authResolver = authResolver;
            return this;
        }

        public Builder configProperties(ToolGatewayConfigProperties.DecoratedMcpServer configProperties) {
            this.configProperties = configProperties;
            return this;
        }

        public Builder asyncRequestCustomizer(McpAsyncHttpClientRequestCustomizer requestCustomizer) {
            Assert.notNull(requestCustomizer, "requestCustomizer must not be null");
            this.requestCustomizer = requestCustomizer;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            Assert.notNull(connectTimeout, "connectTimeout must not be null");
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder resumableStreams(boolean resumableStreams) {
            this.resumableStreams = resumableStreams;
            return this;
        }

        public Builder openConnectionOnStartup(boolean openConnectionOnStartup) {
            this.openConnectionOnStartup = openConnectionOnStartup;
            return this;
        }

        public AuthAwareHttpStreamableClientTransport build() {
            McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);
            McpAsyncHttpClientRequestCustomizer authCustomizer =
                    new AuthAwareRequestCustomizer(authResolver, configProperties);
            McpAsyncHttpClientRequestCustomizer chainedCustomizer =
                    new DelegatingMcpAsyncHttpClientRequestCustomizer(List.of(authCustomizer, requestCustomizer));

            HttpClientStreamableHttpTransport delegate = HttpClientStreamableHttpTransport.builder(baseUri)
                    .endpoint(endpoint)
                    .clientBuilder(clientBuilder)
                    .requestBuilder(requestBuilder)
                    .asyncHttpRequestCustomizer(chainedCustomizer)
                    .connectTimeout(connectTimeout)
                    .resumableStreams(resumableStreams)
                    .openConnectionOnStartup(openConnectionOnStartup)
                    .jsonMapper(jsonMapper)
                    .build();

            return new AuthAwareHttpStreamableClientTransport(
                    delegate,
                    jsonMapper,
                    objectMapper,
                    authResolver,
                    configProperties,
                    baseUri,
                    endpoint);
        }

    }
}
