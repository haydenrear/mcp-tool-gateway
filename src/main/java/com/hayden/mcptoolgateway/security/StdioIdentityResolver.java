package com.hayden.mcptoolgateway.security;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(value = "spring.ai.mcp.server.stdio", havingValue = "true")
@RequiredArgsConstructor
public class StdioIdentityResolver implements IdentityResolver {

    private static final Jwt LOCAL_STDIO_JWT = Jwt.withTokenValue(ToolDecoratorService.SYSTEM_ID)
            .header("alg", "none")
            .claim("sub", ToolDecoratorService.SYSTEM_ID)
            .subject(ToolDecoratorService.SYSTEM_ID)
            .build();

    @Autowired(required = false)
    PasswordOAuth2AuthorizedClientProvider passwordManager;
    @Autowired(required = false)
    ClientCredentialsResolver clientCredentialsResolver;

    @Autowired(required = false)
    ClientRegistration thisS2sClient;
    @Autowired(required = false)
    JwtDecoder jwtDecoder;

    @Override
    public String resolveIdentityToken(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        return resolveJwt(toolState)
                .filter(IdentityResolver::isValidJwt)
                .map(AbstractOAuth2Token::getTokenValue)
                .filter(StringUtils::isNotBlank)
                .orElse(null);
    }

    @Override
    public String resolveUserOrDefault(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        return resolveJwt(toolState)
                .filter(IdentityResolver::isValidJwt)
                .flatMap(IdentityResolver::resolveUserFromJwtToken)
                .orElse(ToolDecoratorService.SYSTEM_ID);
    }

    @Override
    public Optional<Jwt> resolveJwt(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        return Optional.ofNullable(toolState.getJwtToken())
                .flatMap(this::tryDecodeJwt)
                .filter(IdentityResolver::isValidJwt)
                .or(() -> {
                    if (toolState.getUsername() != null && toolState.getPassword() != null && thisS2sClient != null) {
                        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(toolState.getUsername(), toolState.getPassword());
                        OAuth2AuthorizationContext authorizationContext = OAuth2AuthorizationContext
                                .withClientRegistration(thisS2sClient)
                                .principal(token)
                                .build();
                        var auth = passwordManager.authorize(authorizationContext);
                        return Optional.ofNullable(auth)
                                .flatMap(a -> Optional.ofNullable(a.getAccessToken()))
                                .flatMap(accessToken -> Optional.ofNullable(accessToken.getTokenValue()))
                                .filter(StringUtils::isNotBlank)
                                .flatMap(this::tryDecodeJwt);
                    }

                    return Optional.empty();
                })
                .filter(IdentityResolver::isValidJwt)
                .or(() -> Optional.of(LOCAL_STDIO_JWT));
    }

    private @NotNull Optional<Jwt> tryDecodeJwt(String s) {
        try {
            return Optional.ofNullable(jwtDecoder.decode(s));
        } catch (Exception e) {
            log.error("Error decoding jwt.", e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> resolveUserName(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        return Optional.of(resolveUserOrDefault(toolState));
    }

    @Override
    public Mono<String> s2sIdentity(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        return Mono.justOrEmpty(Optional.ofNullable(toolState.getS2sIdentity()))
                .switchIfEmpty(clientCredentialsResolver.clientCredentialsBearer());
    }
}
