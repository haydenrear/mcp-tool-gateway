package io.modelcontextprotocol.client.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.mcptoolgateway.security.AuthResolver;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

public class AuthAwareHttpSseClientTransport extends HttpClientSseClientTransport {


    private static final Logger logger = LoggerFactory.getLogger(AuthAwareHttpSseClientTransport.class);

    /**
     * SSE event type for JSON-RPC messages
     */
    private static final String MESSAGE_EVENT_TYPE = "message";

    /**
     * SSE event type for endpoint discovery
     */
    private static final String ENDPOINT_EVENT_TYPE = "endpoint";

    /**
     * Default SSE endpoint path
     */
    private static final String DEFAULT_SSE_ENDPOINT = "/sse";


    /**
     * JSON object mapper for message serialization/deserialization
     */
    protected ObjectMapper objectMapper;

    /**
     * Flag indicating if the transport is in closing state
     */
    private volatile boolean isClosing = false;

    /**
     * Latch for coordinating endpoint discovery
     */
    private final CountDownLatch closeLatch = new CountDownLatch(1);

    /**
     * Holds the discovered message endpoint URL
     */
    private final AtomicReference<String> messageEndpoint = new AtomicReference<>();

    /**
     * Holds the SSE connection future
     */
    private final AtomicReference<CompletableFuture<Void>> connectionFuture = new AtomicReference<>();

    /**
     * Base URI for the MCP server
     */
    private final URI baseUri;

    /**
     * SSE endpoint path
     */
    private final String sseEndpoint;

    /**
     * SSE client for handling server-sent events. Uses the /sse endpoint
     */
    private final AuthEnabledFlowSseClient sseClient;

    /**
     * HTTP client for sending messages to the server. Uses HTTP POST over the message
     * endpoint
     */
    private final HttpClient httpClient;

    /**
     * HTTP request builder for building requests to send messages to the server
     */
    private final HttpRequest.Builder requestBuilder;

    @Setter
    private AuthResolver resolver;

    public AuthAwareHttpSseClientTransport(String baseUri) {
        this(HttpClient.newBuilder(), baseUri, new ObjectMapper());
    }

    public AuthAwareHttpSseClientTransport(HttpClient.Builder clientBuilder, String baseUri, ObjectMapper objectMapper) {
        this(clientBuilder, baseUri, DEFAULT_SSE_ENDPOINT, objectMapper);
    }

    public AuthAwareHttpSseClientTransport(HttpClient.Builder clientBuilder, String baseUri, String sseEndpoint, ObjectMapper objectMapper) {
        this(clientBuilder, HttpRequest.newBuilder(), baseUri, sseEndpoint, objectMapper);
    }

    public AuthAwareHttpSseClientTransport(HttpClient.Builder clientBuilder, HttpRequest.Builder requestBuilder, String baseUri, String sseEndpoint, ObjectMapper objectMapper) {
        this(clientBuilder.connectTimeout(Duration.ofSeconds(10)).build(), requestBuilder, baseUri, sseEndpoint,
                objectMapper);
    }

    public HttpRequest.Builder newRequestBuilder() {
        return requestBuilder.copy();
    }

    /**
     * Creates a new transport instance with custom HTTP client builder, object mapper,
     * and headers.
     *
     * @param httpClient     the HTTP client to use
     * @param requestBuilder the HTTP request builder to use
     * @param baseUri        the base URI of the MCP server
     * @param sseEndpoint    the SSE endpoint path
     * @param objectMapper   the object mapper for JSON serialization/deserialization
     * @throws IllegalArgumentException if objectMapper, clientBuilder, or headers is null
     */
    AuthAwareHttpSseClientTransport(HttpClient httpClient, HttpRequest.Builder requestBuilder, String baseUri,
                                    String sseEndpoint, ObjectMapper objectMapper) {
        super(httpClient, requestBuilder, baseUri, sseEndpoint, objectMapper);
        Assert.notNull(objectMapper, "ObjectMapper must not be null");
        Assert.hasText(baseUri, "baseUri must not be empty");
        Assert.hasText(sseEndpoint, "sseEndpoint must not be empty");
        Assert.notNull(httpClient, "httpClient must not be null");
        Assert.notNull(requestBuilder, "requestBuilder must not be null");
        this.baseUri = URI.create(baseUri);
        this.sseEndpoint = sseEndpoint;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.requestBuilder = requestBuilder;

        this.sseClient = new AuthEnabledFlowSseClient(this.httpClient, requestBuilder);
    }

    /**
     * Creates a new builder for {@link io.modelcontextprotocol.client.transport.HttpClientSseClientTransport}.
     *
     * @param baseUri the base URI of the MCP server
     * @return a new builder instance
     */
    public static AuthAwareHttpSseClientTransport.Builder authAwareBuilder(String baseUri) {
        return new AuthAwareHttpSseClientTransport.Builder(baseUri);
    }


    private Mono<String> clientCredentialsBearer() {
        return this.resolver.clientCredentialsBearer();
    }

    /**
     * Establishes the SSE connection with the server and sets up message handling.
     *
     * <p>
     * This method:
     * <ul>
     * <li>Initiates the SSE connection</li>
     * <li>Handles endpoint discovery events</li>
     * <li>Processes incoming JSON-RPC messages</li>
     * </ul>
     *
     * @param handler the function to process received JSON-RPC messages
     * @return a Mono that completes when the connection is established
     */
    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        connectionFuture.set(future);

        URI clientUri = Utils.resolveUri(this.baseUri, this.sseEndpoint);
        return clientCredentialsBearer()
                .flatMap(bearer -> {
                    // Build the SSE request now, adding Authorization if available
                    HttpRequest.Builder rb = this.newRequestBuilder()
                            .uri(clientUri)
                            .header("Accept", "text/event-stream")
                            .GET();

                    if (StringUtils.isNotBlank(bearer)) {
                        rb.header("Authorization", bearer);
                    }

                    sseClient.subscribe(clientUri.toString(), new FlowSseClient.SseEventHandler() {
                                @Override
                                public void onEvent(FlowSseClient.SseEvent event) {
                                    if (isClosing) {
                                        return;
                                    }

                                    try {
                                        if (ENDPOINT_EVENT_TYPE.equals(event.type())) {
                                            String endpoint = event.data();
                                            messageEndpoint.set(endpoint);
                                            closeLatch.countDown();
                                            future.complete(null);
                                        } else if (MESSAGE_EVENT_TYPE.equals(event.type())) {
                                            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, event.data());
                                            handler.apply(Mono.just(message)).subscribe();
                                        } else {
                                            logger.error("Received unrecognized SSE event type: {}", event.type());
                                        }
                                    } catch (
                                            IOException e) {
                                        logger.error("Error processing SSE event", e);
                                        future.completeExceptionally(e);
                                    }
                                }

                                @Override
                                public void onError(Throwable error) {
                                    if (!isClosing) {
                                        logger.error("SSE connection error", error);
                                        future.completeExceptionally(error);
                                    }
                                }
                            },
                            rb);
                    return Mono.fromFuture(future);
                });

    }

    private static @NotNull Mono<String> resolve() {
        return Mono.just("");
    }

    /**
     * Sends a JSON-RPC message to the server.
     *
     * <p>
     * This method waits for the message endpoint to be discovered before sending the
     * message. The message is serialized to JSON and sent as an HTTP POST request.
     *
     * @param message the JSON-RPC message to send
     * @return a Mono that completes when the message is sent
     * @throws McpError if the message endpoint is not available or the wait times out
     */
    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        if (isClosing) {
            return Mono.empty();
        }

        try {
            if (!closeLatch.await(10, TimeUnit.SECONDS)) {
                return Mono.error(new McpError("Failed to wait for the message endpoint"));
            }
        } catch (InterruptedException e) {
            return Mono.error(new McpError("Failed to wait for the message endpoint"));
        }

        String endpoint = messageEndpoint.get();
        if (endpoint == null) {
            return Mono.error(new McpError("No message endpoint available"));
        }

        try {
            URI requestUri = Utils.resolveUri(baseUri, endpoint);
            HttpRequest request = parseMessageRequest(message, requestUri);

            return Mono.fromFuture(
                    httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenAccept(response -> {
                        if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 202
                                && response.statusCode() != 206) {
                            logger.error("Error sending message: {}", response.statusCode());
                        }
                    }));

        } catch (IOException e) {
            if (!isClosing) {
                return Mono.error(new RuntimeException("Failed to serialize message", e));
            }
            return Mono.empty();
        }
    }

    private HttpRequest parseMessageRequest(McpSchema.JSONRPCMessage message, URI requestUri) throws IOException {
        HttpRequest request;
        if (message instanceof McpSchema.JSONRPCRequest req) {
            var stripped = AuthResolver.serializeStrippingBearer(req, objectMapper);

            HttpRequest.Builder requestBuilder = newRequestBuilder().uri(requestUri);

            if (StringUtils.isNotBlank(stripped.bearer())) {
                requestBuilder = requestBuilder.header("Authorization", stripped.bearer());
            }

            request = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(stripped.json()))
                    .build();
        } else {
            String jsonText = this.objectMapper.writeValueAsString(message);
            request = newRequestBuilder().uri(requestUri)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonText))
                    .build();
        }
        return request;
    }

    /**
     * Gracefully closes the transport connection.
     *
     * <p>
     * Sets the closing flag and cancels any pending connection future. This prevents new
     * messages from being sent and allows ongoing operations to complete.
     *
     * @return a Mono that completes when the closing process is initiated
     */
    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            isClosing = true;
            CompletableFuture<Void> future = connectionFuture.get();
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        });
    }

    /**
     * Unmarshal data to the specified type using the configured object mapper.
     *
     * @param data    the data to unmarshal
     * @param typeRef the type reference for the target type
     * @param <T>     the target type
     * @return the unmarshalled object
     */
    @Override
    public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
        return this.objectMapper.convertValue(data, typeRef);
    }

    public static class Builder {

        private String baseUri;

        private String sseEndpoint = DEFAULT_SSE_ENDPOINT;

        private HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10));

        private ObjectMapper objectMapper = new ObjectMapper();
        private AuthResolver authResolver;

        private HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .header("Content-Type", "application/json");

        /**
         * Creates a new builder instance.
         */
        Builder() {
            // Default constructor
        }

        /**
         * Creates a new builder with the specified base URI.
         *
         * @param baseUri the base URI of the MCP server
         * @deprecated Use {@link AuthAwareHttpSseClientTransport#builder(String)} instead.
         * This constructor is deprecated and will be removed or made {@code protected} or
         * {@code private} in a future release.
         */
        @Deprecated(forRemoval = true)
        public Builder(String baseUri) {
            Assert.hasText(baseUri, "baseUri must not be empty");
            this.baseUri = baseUri;
        }

        /**
         * Sets the base URI.
         *
         * @param baseUri the base URI
         * @return this builder
         */
        AuthAwareHttpSseClientTransport.Builder baseUri(String baseUri) {
            Assert.hasText(baseUri, "baseUri must not be empty");
            this.baseUri = baseUri;
            return this;
        }

        /**
         * Sets the SSE endpoint path.
         *
         * @param sseEndpoint the SSE endpoint path
         * @return this builder
         */
        public AuthAwareHttpSseClientTransport.Builder sseEndpoint(String sseEndpoint) {
            Assert.hasText(sseEndpoint, "sseEndpoint must not be empty");
            this.sseEndpoint = sseEndpoint;
            return this;
        }

        /**
         * Sets the HTTP client builder.
         *
         * @param clientBuilder the HTTP client builder
         * @return this builder
         */
        public AuthAwareHttpSseClientTransport.Builder clientBuilder(HttpClient.Builder clientBuilder) {
            Assert.notNull(clientBuilder, "clientBuilder must not be null");
            this.clientBuilder = clientBuilder;
            return this;
        }

        /**
         * Customizes the HTTP client builder.
         *
         * @param clientCustomizer the consumer to customize the HTTP client builder
         * @return this builder
         */
        public AuthAwareHttpSseClientTransport.Builder customizeClient(final Consumer<HttpClient.Builder> clientCustomizer) {
            Assert.notNull(clientCustomizer, "clientCustomizer must not be null");
            clientCustomizer.accept(clientBuilder);
            return this;
        }

        /**
         * Sets the HTTP request builder.
         *
         * @param requestBuilder the HTTP request builder
         * @return this builder
         */
        public AuthAwareHttpSseClientTransport.Builder requestBuilder(HttpRequest.Builder requestBuilder) {
            Assert.notNull(requestBuilder, "requestBuilder must not be null");
            this.requestBuilder = requestBuilder;
            return this;
        }

        /**
         * Customizes the HTTP client builder.
         *
         * @param requestCustomizer the consumer to customize the HTTP request builder
         * @return this builder
         */
        public AuthAwareHttpSseClientTransport.Builder customizeRequest(final Consumer<HttpRequest.Builder> requestCustomizer) {
            Assert.notNull(requestCustomizer, "requestCustomizer must not be null");
            requestCustomizer.accept(requestBuilder);
            return this;
        }

        /**
         * Sets the object mapper for JSON serialization/deserialization.
         *
         * @param objectMapper the object mapper
         * @return this builder
         */
        public AuthAwareHttpSseClientTransport.Builder objectMapper(ObjectMapper objectMapper) {
            Assert.notNull(objectMapper, "objectMapper must not be null");
            this.objectMapper = objectMapper;
            return this;
        }

        public AuthAwareHttpSseClientTransport.Builder authResolver(AuthResolver objectMapper) {
            Assert.notNull(objectMapper, "objectMapper must not be null");
            this.authResolver = objectMapper;
            return this;
        }

        /**
         * Builds a new {@link AuthAwareHttpSseClientTransport} instance.
         *
         * @return a new transport instance
         */
        public AuthAwareHttpSseClientTransport build() {
            var t = new AuthAwareHttpSseClientTransport(clientBuilder.build(), requestBuilder, baseUri, sseEndpoint,
                    objectMapper);
            t.setResolver(this.authResolver);
            return t;
        }

    }
}
