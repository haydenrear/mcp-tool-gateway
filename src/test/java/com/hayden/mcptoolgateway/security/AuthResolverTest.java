package com.hayden.mcptoolgateway.security;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.hayden.mcptoolgateway.TestUtils.stubToken;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test"})
@TestPropertySource(properties = {
        "http-mcp.enabled=true",
        "spring.ai.mcp.server.stdio=false"})
class AuthResolverTest {

    @Autowired
    AuthResolver authResolver;

    private static final WireMockServer wireMockServer = new WireMockServer(9999);

    @BeforeAll
    public static void setUp() {
        wireMockServer.start();
        setMocks();
    }

    private static void setMocks() {
        configureFor("localhost", 9999);
        stubToken("wm-token");
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
    void testResolve() {
        var client = authResolver.clientCredentialsBearer()
                .block();

        assertThat(client).isNotNull();
        assertThat(client).isNotBlank();
    }

}