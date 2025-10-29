package com.hayden.mcptoolgateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import io.modelcontextprotocol.spec.McpSchema;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

import static com.hayden.mcptoolgateway.tool.ToolDecoratorService.AUTH_BODY_FIELD;

public interface IdentityResolver {

    record BodyAndBearer(String json, String bearer) { }

    static boolean isValidJwt(Jwt jwt) {
        return jwt != null && (jwt.getExpiresAt() == null || Instant.now().isBefore(jwt.getExpiresAt()));
    }

    static @NotNull Optional<String> resolveUserFromJwtToken(Jwt j) {
        return Optional.ofNullable(j.getSubject());
    }

    static @NotNull Optional<String> resolveUserFromJwtToken(String bearer, JwtDecoder decoder) {
        return Optional.ofNullable(bearer)
                .flatMap(s -> Optional.ofNullable(decoder.decode(s)))
                .flatMap(IdentityResolver::resolveUserFromJwtToken);
    }


    static String resolveBearerTokenHeader() {
        // Works when called inside a reactive chain (RouterFunction handlers etc.)
        return toBearer(
                SecurityContextHolder.getContext() != null ? SecurityContextHolder.getContext().getAuthentication() : null);
    }

    static String resolveIdentityTokenBearer() {
        // Works when called inside a reactive chain (RouterFunction handlers etc.)
        return toBearer(
                SecurityContextHolder.getContext() != null ? SecurityContextHolder.getContext().getAuthentication() : null);
    }

    static String toUserId(Authentication auth) {
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

    static String toBearer(Authentication auth) {
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

    static BodyAndBearer serializeStrippingBearer(McpSchema.JSONRPCRequest message,
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

    default String resolveUserOrDefault(ToolDecoratorService.McpServerToolState toolState) {
        return resolveUserOrDefault(toolState.deployableMcpServer());
    }

    default Optional<Jwt> resolveJwt(ToolDecoratorService.McpServerToolState toolState){
        return resolveJwt(toolState.deployableMcpServer());
    }

    default Optional<String> resolveUserName(ToolDecoratorService.McpServerToolState toolState){
        return resolveUserName(toolState.deployableMcpServer());
    }

    default Mono<String> s2sIdentity(ToolDecoratorService.McpServerToolState toolState){
        return s2sIdentity(toolState.deployableMcpServer());
    }

    default String resolveIdentityToken(ToolDecoratorService.McpServerToolState toolState) {
        return resolveIdentityToken(toolState.deployableMcpServer());
    }

    String resolveIdentityToken(ToolGatewayConfigProperties.DecoratedMcpServer toolState);

    String resolveUserOrDefault(ToolGatewayConfigProperties.DecoratedMcpServer toolState);

    Optional<Jwt> resolveJwt(ToolGatewayConfigProperties.DecoratedMcpServer toolState);

    Optional<String> resolveUserName(ToolGatewayConfigProperties.DecoratedMcpServer toolState);

    Mono<String> s2sIdentity(ToolGatewayConfigProperties.DecoratedMcpServer toolState);
}
