package com.hayden.mcptoolgateway.security;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Component
@ConditionalOnProperty(value = "spring.ai.mcp.server.stdio", havingValue = "true")
public class StdioIdentityResolver implements IdentityResolver {

    private static final Jwt LOCAL_STDIO_JWT = Jwt.withTokenValue(ToolDecoratorService.SYSTEM_ID)
            .header("alg", "none")
            .claim("sub", ToolDecoratorService.SYSTEM_ID)
            .subject(ToolDecoratorService.SYSTEM_ID)
            .build();

    @Override
    public String resolveIdentityToken(ToolDecoratorService.McpServerToolState toolState) {
        return ToolDecoratorService.SYSTEM_ID;
    }

    @Override
    public String resolveUserOrDefault(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        return ToolDecoratorService.SYSTEM_ID;
    }

    @Override
    public Optional<Jwt> resolveJwt(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        return Optional.of(LOCAL_STDIO_JWT);
    }

    @Override
    public Optional<String> resolveUserName(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        return Optional.of(resolveUserOrDefault(toolState));
    }

    @Override
    public Mono<String> s2sIdentity(ToolGatewayConfigProperties.DecoratedMcpServer toolState) {
        return Mono.just(ToolDecoratorService.SYSTEM_ID);
    }
}
