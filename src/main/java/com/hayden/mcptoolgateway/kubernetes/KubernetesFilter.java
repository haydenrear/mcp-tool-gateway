package com.hayden.mcptoolgateway.kubernetes;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.security.IdentityResolver;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

import static com.hayden.commitdiffcontext.cdc_config.AuthorizationServerConfigProps.UNRESTRICTED;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "spring.ai.mcp.server.stdio", havingValue = "false", matchIfMissing = true)
public class KubernetesFilter extends OncePerRequestFilter {

    private final ToolDecoratorService toolDecoratorService;

    private final K3sService deployment;

    private final IdentityResolver identityResolver;

    @Override
    protected void doFilterInternal(@NotNull HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain) throws ServletException, IOException {
        if (UNRESTRICTED.matches(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        var user = identityResolver.resolveUserName((ToolGatewayConfigProperties.DecoratedMcpServer) null);

        if (user.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized - no authentication found.");
            return;
        }

        String host;

        var d = deployment.doDeployGetValidDeployment(user.get());

        if (!d.success()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Failed to deploy or get deployment - %s.".formatted(Optional.ofNullable(d.err()).orElse("unknown error.")));
            return;
        }
        else  {
            host = d.host();
        }

        var a = toolDecoratorService
                .createAddClient(new ToolDecoratorService.AddClient("cdc", user.get(), host));

        if (!a.success()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Failed to resolve MCP client - %s.".formatted(Optional.ofNullable(a.underlying()).map(ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult::err).orElse("unknown error.")));
            return;
        }

        filterChain.doFilter(request, response);
    }
}
