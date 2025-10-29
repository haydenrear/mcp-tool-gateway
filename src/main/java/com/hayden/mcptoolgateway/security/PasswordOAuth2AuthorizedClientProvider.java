package com.hayden.mcptoolgateway.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.endpoint.DefaultPasswordTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2PasswordGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "spring.ai.mcp.server.stdio", havingValue = "true")
public class PasswordOAuth2AuthorizedClientProvider implements OAuth2AuthorizedClientProvider {

    private final DefaultPasswordTokenResponseClient passwordTokenResponseClient;

    @Override
    public OAuth2AuthorizedClient authorize(OAuth2AuthorizationContext context) {
        ClientRegistration clientRegistration = context.getClientRegistration();
        var tokenResponse = passwordTokenResponseClient.getTokenResponse(new OAuth2PasswordGrantRequest(
                clientRegistration, context.getPrincipal().getName(), String.valueOf(context.getPrincipal().getCredentials())));

        return new OAuth2AuthorizedClient(clientRegistration, context.getPrincipal().getName(), tokenResponse.getAccessToken());
    }

}
