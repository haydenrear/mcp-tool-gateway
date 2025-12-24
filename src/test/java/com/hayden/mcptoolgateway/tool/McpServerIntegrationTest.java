package com.hayden.mcptoolgateway.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.hayden.mcptoolgateway.kubernetes.KubernetesFilter;
import com.hayden.mcptoolgateway.kubernetes.UserMetadata;
import com.hayden.mcptoolgateway.kubernetes.UserMetadataRepository;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.DelegatingHttpClientStreamableHttpTransport;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.hayden.mcptoolgateway.TestUtils.stubToken;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for CodeSearchMcpTools focusing on behavior validation.
 * Tests code search, AST parsing, node extraction, and search functionality
 * using real files and minimal mocking to ensure robustness.
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"inttest"})
@TestPropertySource(properties = {"http-mcp.enabled=true", "spring.ai.mcp.server.stdio=false"})
class McpServerIntegrationTest {

    @Autowired
    private NimbusJwtEncoder jwtEncoder;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMetadataRepository userMetadataRepository;

    @Autowired
    private McpServerToolStates toolStates;

    @MockitoSpyBean
    KubernetesFilter k3sService;

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


    @SneakyThrows
    @Test
    void whenTokenThenConnectsWithMcpClient() {
        var fc = ArgumentCaptor.forClass(FilterChain.class);
        var req = ArgumentCaptor.forClass(HttpServletRequest.class);
        var res = ArgumentCaptor.forClass(HttpServletResponse.class);
        Mockito.doAnswer(inv -> {
                    fc.getValue().doFilter(req.getValue(), res.getValue());
                    return null;
                }).when(k3sService).doFilter(req.capture(), res.capture(), fc.capture());
        try (var m = McpClient.sync(
                        DelegatingHttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                                .endpoint("/mcp")
                                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                                .customizeRequest(r -> r.header("Authorization", "Bearer " + accessToken))
                                .build())
                .build()) {
            var initialized = m.initialize();
            var listed = m.listTools();
            var called = m.callTool(new McpSchema.CallToolRequest("add-tool-server", Map.of("server_name", "test-rollback-server-2")));

            assertThat(called.isError()).isFalse();
            assertThat(called.content().stream().anyMatch(c -> c instanceof McpSchema.TextContent t && t.text().contains("aTool") && t.text().contains("test-rollback-server-2"))).isTrue();

            var listedAgain = m.listTools();
            assertThat(listed.tools().size()).isEqualTo(listedAgain.tools().size());
            log.info("Found list tools {}", listed);
        }

        Mockito.reset(k3sService);
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
