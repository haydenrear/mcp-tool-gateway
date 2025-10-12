package io.modelcontextprotocol.client.transport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Optional;

import static com.hayden.mcptoolgateway.tool.ToolDecoratorService.AUTH_BODY_FIELD;

@Component
public class AuthResolver {

    public Mono<String> clientCredentialsBearer() {
        // Works when called inside a reactive chain (RouterFunction handlers etc.)
        return Mono.empty();
    }

    public static String resolveUserOrDefault() {
        // Works when called inside a reactive chain (RouterFunction handlers etc.)
        return Optional.ofNullable(resolveUser())
                .orElse("default");
    }

    public static String resolveUser() {
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
            try { return "Bearer " + jwt.getToken().getTokenValue(); } catch (Throwable ignored) {}
            Object creds = jwt.getCredentials();
            if (creds instanceof String s && !s.isBlank()) return "Bearer " + s;
        }

        // Opaque bearer
        if (auth instanceof BearerTokenAuthentication bta) {
            return "Bearer " + bta.getToken().getTokenValue();
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

