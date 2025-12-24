package com.hayden.mcptoolgateway.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.hayden.mcptoolgateway.kubernetes.K3sService;
import com.hayden.mcptoolgateway.kubernetes.KubernetesFilter;
import com.hayden.mcptoolgateway.kubernetes.UserMetadata;
import com.hayden.mcptoolgateway.kubernetes.UserMetadataRepository;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.hayden.mcptoolgateway.TestUtils.stubToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for CodeSearchMcpTools focusing on behavior validation.
 * Tests code search, AST parsing, node extraction, and search functionality
 * using real files and minimal mocking to ensure robustness.
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"inttest"})
@TestPropertySource(properties = {"http-mcp.enabled=true", "spring.ai.mcp.server.stdio=false"})
class McpServerHttpSecurityIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private NimbusJwtEncoder jwtEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMetadataRepository userMetadataRepository;

    @Autowired
    private McpServerToolStates toolStates;

    private static final WireMockServer wireMockServer = new WireMockServer(9999);

    @BeforeAll
    public static void setUp() {
        wireMockServer.start();
        wireMockServer.resetAll();
        setMocks();
    }

    private static void setMocks() {
        configureFor("localhost", 9999);
        stubFor(get(urlEqualTo("/api/v1/credits/get")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"hasCredits\":true,\"remaining\":5}")));
        stubFor(post(urlEqualTo("/api/v1/credits/get-and-decrement")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"hasCredits\":true,\"remaining\":4,\"consumed\":1}")));
        log.info("WireMock server started on port {}", wireMockServer.port());
    }

    private static String accessToken;

    @BeforeEach
    public void setupToken() {
        if (accessToken == null) {
            accessToken = obtainAccessToken();
            stubToken(accessToken);
        }
        toolStates.addUpdateToolState("cdctest-user", ToolDecoratorService.McpServerToolState.builder()
                .added(new ArrayList<>())
                .build()
                .initialize());
        userMetadataRepository.findByUserId("test-user")
                .ifPresentOrElse(
                        um -> {},
                        () -> {
                            var meta = userMetadataRepository.findByUserId("test-user")
                                    .orElseGet(() -> UserMetadata.builder()
                                            .userId("test-user")
                                            .id("test-user")
                                            .build());
                            meta.setUnitName("test");
                            meta.setNamespace("ns");
                            meta.setResolvedHost("http://localhost:%s".formatted(port));
                            meta.setLastValidatedAt(OffsetDateTime.now());
                            userMetadataRepository.save(meta);
                        });
    }

    @AfterEach
    public void resetWireMockServer() {
        wireMockServer.resetAll();
        setMocks();
    }

    @AfterAll
    public static void after() {
        wireMockServer.resetAll();
        wireMockServer.stop();
        wireMockServer.shutdown();
    }

    @Test
    @SneakyThrows
    void whenTokenThenConnects() {
        McpSchema.InitializeRequest initRequest = new McpSchema.InitializeRequest(
                McpSchema.LATEST_PROTOCOL_VERSION,
                new McpSchema.ClientCapabilities(null, null, null, null),
                new McpSchema.Implementation("test-client", "1.0.0"));
        McpSchema.JSONRPCRequest rpcRequest = new McpSchema.JSONRPCRequest(
                McpSchema.JSONRPC_VERSION,
                McpSchema.METHOD_INITIALIZE,
                1,
                initRequest);

        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(rpcRequest)))
                .build();

        var responseCode = new AtomicInteger(-1);

        try (var client = HttpClient.newHttpClient()) {
            var initRequestSend = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .thenApply(response -> {
                        responseCode.set(response.statusCode());
                        // IMPORTANT: close the stream so the server sees EOF
                        HttpResponse<InputStream> resp = response;
                        try (var is = new BufferedInputStream(resp.body())) {
//                            String msg = new String(is.readAllBytes(), Charset.defaultCharset());
//                            log.info(msg);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        if (response.statusCode() == 200) {
                            return response;
                        } else {
                            throw new RuntimeException("Failed to initialize MCP session: " + response.statusCode());
                        }

                    });

            await().atMost(Duration.ofSeconds(3)).until(() -> responseCode.get() == 200);
            assertThat(initRequestSend).isCompleted();
            assertThat(responseCode.get() == 200).isTrue();
            client.shutdownNow();
        }

    }

    @Test
    void whenNoTokenThenFails() {

        try (var m = McpClient.sync(
                        HttpClientStreamableHttpTransport.builder("")
                                .endpoint("/mcp")
                                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                                .build())
                .build()) {
            var initialized = m.initialize();
            throw new AssertionError("Did start.");
        } catch (Exception ignored) {

        }
    }

    @Test
    void whenNoTokenThenFailsWithMcpClient() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        try (var client = HttpClient.newHttpClient()) {
            HttpResponse<Void> send = client.send(request, HttpResponse.BodyHandlers.discarding());
            var response = send.statusCode();
            assertThat(response).isEqualTo(401);
        }
    }

    private String obtainAccessToken() {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("self")               // Optional but useful
                .subject("test-user")         // Who the token is for
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                // Optional: add scopes/authorities if you need them later
                // .claim("scope", "sse:connect")
                .claim("hello", "goodbye")    // Your custom claim from before
                .build();

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .type("JWT")
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
