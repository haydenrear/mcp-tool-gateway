package com.hayden.mcptoolgateway.security;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(value = "spring.ai.mcp.server.stdio", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class OAuth2AuthorizationServerResolver implements IdentityResolver {

    private final JwtDecoder jwtDecoder;
    private final ClientCredentialsResolver clientCredentialsResolver;

    /** Reactive wrapper (true non-blocking from caller’s POV): */
    public Mono<String> clientCredentialsBearer() {
        return clientCredentialsResolver.clientCredentialsBearer();
    }

    @Override
    public String resolveIdentityToken(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        return IdentityResolver.resolveIdentityTokenBearer();
    }

    @Override
    public String resolveUserOrDefault(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        // Works when called inside a reactive chain (RouterFunction handlers etc.)
        return resolveUserName(toolState)
                .orElse(ToolDecoratorService.SYSTEM_ID);
    }

    @Override
    public Optional<Jwt> resolveJwt(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        var bearer = IdentityResolver.toBearer(
                SecurityContextHolder.getContext() != null ? SecurityContextHolder.getContext().getAuthentication() : null);
        return Optional.ofNullable(bearer)
                .flatMap(s -> Optional.ofNullable(jwtDecoder.decode(s)));
    }

    @Override
    public Optional<String> resolveUserName(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        // Works when called inside a reactive chain (RouterFunction handlers etc.)
        var bearer = IdentityResolver.toBearer(
                SecurityContextHolder.getContext() != null ? SecurityContextHolder.getContext().getAuthentication() : null);
        return IdentityResolver.resolveUserFromJwtToken(bearer, jwtDecoder);
    }

    @Override
    public Mono<String> s2sIdentity(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        return this.clientCredentialsBearer();
    }

}

