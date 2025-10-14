package com.hayden.mcptoolgateway.security;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hayden.mcptoolgateway.tool.ToolDecorator;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static com.hayden.mcptoolgateway.tool.ToolDecoratorService.AUTH_BODY_FIELD;

@Component
public class AuthResolver {

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

        var request = OAuth2AuthorizeRequest
                .withClientRegistrationId("cdc-oauth2-client")
                .principal(principal)
                .attributes(s -> {})
                .build();

        OAuth2AuthorizationContext authorizationContext = OAuth2AuthorizationContext.withClientRegistration(clientRegistration).principal(principal)
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


    public String resolveUserOrDefault() {
        // Works when called inside a reactive chain (RouterFunction handlers etc.)
        return resolveUserName()
                .orElse(ToolDecoratorService.SYSTEM_ID);
    }


    public Optional<String> resolveUserName() {
        // Works when called inside a reactive chain (RouterFunction handlers etc.)
        var bearer = toBearer(
                SecurityContextHolder.getContext() != null ? SecurityContextHolder.getContext().getAuthentication() : null);
        return Optional.ofNullable(bearer)
                .flatMap(s -> Optional.ofNullable(jwtDecoder.decode(s)))
                .flatMap(j -> Optional.ofNullable(j.getSubject()));
    }

    public static String resolveBearerTokenHeader() {
        // Works when called inside a reactive chain (RouterFunction handlers etc.)
        return toBearer(
                SecurityContextHolder.getContext() != null ? SecurityContextHolder.getContext().getAuthentication() : null);
    }

    public static String resolveBearerHeader() {
        // Works when called inside a reactive chain (RouterFunction handlers etc.)
        return toBearer(
                SecurityContextHolder.getContext() != null ? SecurityContextHolder.getContext().getAuthentication() : null);
    }

    public static String toUserId(Authentication auth) {
        if (auth == null)
            return null;

        // Resource server JWT
        if (auth instanceof JwtAuthenticationToken jwt) {
            return jwt.getName();
        }

        // Opaque bearer
        if (auth instanceof BearerTokenAuthentication bta) {
            return bta.getName();
        }

        return null;
    }

    public static String toBearer(Authentication auth) {
        if (auth == null) return null;

        // Resource server JWT
        if (auth instanceof JwtAuthenticationToken jwt) {
            try { return jwt.getToken().getTokenValue(); } catch (Throwable ignored) {}
            Object creds = jwt.getCredentials();
            if (creds instanceof String s && !s.isBlank())
                return s;
        }

        // Opaque bearer
        if (auth instanceof BearerTokenAuthentication bta) {
            return bta.getToken().getTokenValue();
        }

        return null;
    }

    public record BodyAndBearer(String json, String bearer) { }

    public static BodyAndBearer serializeStrippingBearer(McpSchema.JSONRPCRequest message,
                                                         ObjectMapper objectMapper) throws IOException {
        // Serialize to a tree so we can surgically remove the token field.
        ObjectNode root = objectMapper.valueToTree(message);
        JsonNode params = root.get("params");
        String bearer = null;
        if (params instanceof ObjectNode obj && obj.has(AUTH_BODY_FIELD)) {
            JsonNode tokenNode = obj.get(AUTH_BODY_FIELD);
            if (tokenNode != null && !tokenNode.isNull()) {
                bearer = tokenNode.asText(null);
            }
            obj.remove(AUTH_BODY_FIELD); // strip token from outgoing payload
        }
        String json = objectMapper.writeValueAsString(root);
        return new BodyAndBearer(json, bearer);
    }
}

