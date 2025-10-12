package com.hayden.mcptoolgateway.kubernetes;

import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import io.modelcontextprotocol.client.transport.AuthResolver;
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

    private final AuthResolver authResolver;

    private final K3sService deployment;

    @Override
    protected void doFilterInternal(@NotNull HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        var d = deployment.doDeployGetValidDeployment();

        if (!d.success()) {
            response.getWriter()
                    .write("Failed to deploy or get deployment - %s.".formatted(Optional.ofNullable(d.err()).orElse("unknown error.")));
            return;
        }

        var a = toolDecoratorService.createAddClient(new ToolDecoratorService.AddClient("cdc", AuthResolver.resolveUser(), ""));

        if (!a.success()) {
            response.getWriter()
                    .write("Failed to resolve MCP client - %s.".formatted(Optional.ofNullable(a.underlying()).map(ToolDecoratorService.SetSyncClientResult::err).orElse("unknown error.")));
            return;
        }

        filterChain.doFilter(request, response);
    }
}
