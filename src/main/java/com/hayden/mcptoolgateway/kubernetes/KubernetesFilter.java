package com.hayden.mcptoolgateway.kubernetes;

import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import com.hayden.mcptoolgateway.security.AuthResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class KubernetesFilter extends OncePerRequestFilter {

    private final ToolDecoratorService toolDecoratorService;

    private final K3sService deployment;

    private final UserMetadataRepository userMetadataRepository;

    private final AuthResolver authResolver;

    @Override
    protected void doFilterInternal(@NotNull HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain) throws ServletException, IOException {

        var user = authResolver.resolveUserName();

        if (user.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "unauthorized - no authentication found.");
            return;
        }

        String host;
        var meta = userMetadataRepository.findByUserId(user.get());
        if (meta.isPresent() && meta.get().getResolvedHost() != null && !meta.get().getResolvedHost().isBlank()) {
            host = meta.get().getResolvedHost();
        } else {
            var d = deployment.doDeployGetValidDeployment();

            if (!d.success()) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Failed to deploy or get deployment - %s.".formatted(Optional.ofNullable(d.err()).orElse("unknown error.")));
                return;
            }
            host = d.host();
        }

        var a = Optional.of(toolDecoratorService
                .createAddClient(new ToolDecoratorService.AddClient("cdc", user.get(), host)));

        if (a.isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Failed to resolve MCP client - unkown error.");
            return;
        }

        if (!a.get().success()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Failed to resolve MCP client - %s.".formatted(Optional.ofNullable(a.get().underlying()).map(ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult::err).orElse("unknown error.")));
            return;
        }

        filterChain.doFilter(request, response);
    }
}
