package com.hayden.mcptoolgateway.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(value = "spring.ai.mcp.server.stdio", havingValue = "false", matchIfMissing = true)
public class ClientCredentialsResolver {
    @Autowired
    ClientRegistrationRepository clientRegistrationRepository;
    @Autowired
    ClientCredentialsOAuth2AuthorizedClientProvider manager;

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

    public Mono<String> clientCredentialsBearer() {
        return reactor.core.publisher.Mono
                .fromCallable(this::clientCredentialsBearerBlocking)
                .flatMap(Mono::justOrEmpty)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

}
