package io.modelcontextprotocol.client.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;

public class AuthEnabledFlowSseClient {

    private final HttpClient httpClient;

    private final HttpRequest.Builder requestBuilder;

    /**
     * Pattern to extract the data content from SSE data field lines. Matches lines
     * starting with "data:" and captures the remaining content.
     */
    private static final Pattern EVENT_DATA_PATTERN = Pattern.compile("^data:(.+)$", Pattern.MULTILINE);

    /**
     * Pattern to extract the event ID from SSE id field lines. Matches lines starting
     * with "id:" and captures the ID value.
     */
    private static final Pattern EVENT_ID_PATTERN = Pattern.compile("^id:(.+)$", Pattern.MULTILINE);

    /**
     * Pattern to extract the event type from SSE event field lines. Matches lines
     * starting with "event:" and captures the event type.
     */
    private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("^event:(.+)$", Pattern.MULTILINE);

    public AuthEnabledFlowSseClient(HttpClient httpClient, HttpRequest.Builder requestBuilder) {
        this.httpClient = httpClient;
        this.requestBuilder = requestBuilder;
    }

    public void subscribe(String url, io.modelcontextprotocol.client.transport.FlowSseClient.SseEventHandler eventHandler) {
        this.subscribe(url, eventHandler, this.requestBuilder);
    }

    /**
     * Subscribes to an SSE endpoint and processes the event stream.
     *
     * <p>
     * This method establishes a connection to the specified URL and begins processing the
     * SSE stream. Events are parsed and delivered to the provided event handler. The
     * connection remains active until either an error occurs or the server closes the
     * connection.
     *
     * @param url          the SSE endpoint URL to connect to
     * @param eventHandler the handler that will receive SSE events and error
     *                     notifications
     * @throws RuntimeException if the connection fails with a non-200 status code
     */
    public void subscribe(String url, io.modelcontextprotocol.client.transport.FlowSseClient.SseEventHandler eventHandler,
                          HttpRequest.Builder requestBuilder) {
        HttpRequest request = requestBuilder.uri(URI.create(url))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .GET()
                .build();

        StringBuilder eventBuilder = new StringBuilder();
        AtomicReference<String> currentEventId = new AtomicReference<>();
        AtomicReference<String> currentEventType = new AtomicReference<>("message");

        Flow.Subscriber<String> lineSubscriber = new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String line) {
                if (line.isEmpty()) {
                    // Empty line means end of event
                    if (eventBuilder.length() > 0) {
                        String eventData = eventBuilder.toString();
                        io.modelcontextprotocol.client.transport.FlowSseClient.SseEvent event = new io.modelcontextprotocol.client.transport.FlowSseClient.SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());
                        eventHandler.onEvent(event);
                        eventBuilder.setLength(0);
                    }
                } else {
                    if (line.startsWith("data:")) {
                        var matcher = EVENT_DATA_PATTERN.matcher(line);
                        if (matcher.find()) {
                            eventBuilder.append(matcher.group(1).trim()).append("\n");
                        }
                    } else if (line.startsWith("id:")) {
                        var matcher = EVENT_ID_PATTERN.matcher(line);
                        if (matcher.find()) {
                            currentEventId.set(matcher.group(1).trim());
                        }
                    } else if (line.startsWith("event:")) {
                        var matcher = EVENT_TYPE_PATTERN.matcher(line);
                        if (matcher.find()) {
                            currentEventType.set(matcher.group(1).trim());
                        }
                    }
                }
                subscription.request(1);
            }

            @Override
            public void onError(Throwable throwable) {
                eventHandler.onError(throwable);
            }

            @Override
            public void onComplete() {
                // Handle any remaining event data
                if (eventBuilder.length() > 0) {
                    String eventData = eventBuilder.toString();
                    io.modelcontextprotocol.client.transport.FlowSseClient.SseEvent event = new io.modelcontextprotocol.client.transport.FlowSseClient.SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());
                    eventHandler.onEvent(event);
                }
            }
        };

        Function<Flow.Subscriber<String>, HttpResponse.BodySubscriber<Void>> subscriberFactory = subscriber -> HttpResponse.BodySubscribers
                .fromLineSubscriber(subscriber);

        CompletableFuture<HttpResponse<Void>> future = this.httpClient.sendAsync(request,
                info -> subscriberFactory.apply(lineSubscriber));

        future.thenAccept(response -> {
            int status = response.statusCode();
            if (status != 200 && status != 201 && status != 202 && status != 206) {
                throw new RuntimeException("Failed to connect to SSE stream. Unexpected status code: " + status);
            }
        }).exceptionally(throwable -> {
            eventHandler.onError(throwable);
            return null;
        });
    }

}
