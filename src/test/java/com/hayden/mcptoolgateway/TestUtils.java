package com.hayden.mcptoolgateway;

import org.springframework.security.oauth2.jwt.JwtEncoder;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;

public interface TestUtils {

    static void stubToken(String token) {
        // Spring sends client_id/secret via Basic auth by default for client_credentials
        stubFor(post(urlEqualTo("/oauth2/token"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=client_credentials"))
                // accept either Basic or body creds; uncomment if you want to enforce Basic:
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"%s\",\"token_type\":\"Bearer\",\"expires_in\":3600}".formatted(token))));
    }

}
