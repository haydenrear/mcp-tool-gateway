package com.hayden.mcptoolgateway.security;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import jakarta.annotation.PostConstruct;
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
public class OAuth2AuthorizationServerResolver implements IdentityResolver {

    @Autowired
    ClientRegistrationRepository clientRegistrationRepository;
    @Autowired
    ClientCredentialsOAuth2AuthorizedClientProvider manager;
    @Autowired
    JwtDecoder jwtDecoder;

    private ClientRegistration clientRegistration;

    @PostConstruct
    public void init() {
        clientRegistration = clientRegistrationRepository.findByRegistrationId("cdc-oauth2-client");
    }

    private Optional<String> clientCredentialsBearerBlocking() {
        Collection<GrantedAuthority> authorities = clientRegistration.getScopes().stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toCollection(() -> {
                    Collection<GrantedAuthority> s = new ArrayList<>();
                    return s;
                }));

        var principal = new AnonymousAuthenticationToken("client-credentials", "cdc-oauth2-client", authorities);

        OAuth2AuthorizationContext authorizationContext = OAuth2AuthorizationContext
                .withClientRegistration(clientRegistration)
                .principal(principal)
                .build();
        var auth = manager.authorize(authorizationContext);

        return Optional.ofNullable(auth)
                .flatMap(o -> Optional.ofNullable(o.getAccessToken().getTokenValue()));
    }

    /** Reactive wrapper (true non-blocking from caller’s POV): */
    public Mono<String> clientCredentialsBearer() {
        return reactor.core.publisher.Mono
                .fromCallable(this::clientCredentialsBearerBlocking)
                .flatMap(Mono::justOrEmpty)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }


    @Override
    public String resolveIdentityToken(ToolDecoratorService.McpServerToolState toolState) {
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
        return Optional.ofNullable(bearer)
                .flatMap(s -> Optional.ofNullable(jwtDecoder.decode(s)))
                .flatMap(j -> Optional.ofNullable(j.getSubject()));
    }

    @Override
    public Mono<String> s2sIdentity(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        return this.clientCredentialsBearer();
    }

}

