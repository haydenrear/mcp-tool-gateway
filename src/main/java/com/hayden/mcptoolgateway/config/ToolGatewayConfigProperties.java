package com.hayden.mcptoolgateway.config;

import com.hayden.commitdiffmodel.codegen.types.ExecutionType;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@ConfigurationProperties(prefix = "gateway")
@Component
@Data
public class ToolGatewayConfigProperties {

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    @ToString
    public static class DeployableMcpServer {

        public DeployableMcpServer(String name, String command, Path directory, Path binary) {
            this.name = name;
            this.command = command;
            this.directory = directory;
            this.copyToArtifactPath = binary;
        }

        private String name;
        private String command;
        private String arguments;
        private Set<String> failurePatterns;
        private Set<String> successPatterns;
        private Path directory;
        private Path copyFromArtifactPath;
        private Path copyToArtifactPath;
        private Path mcpDeployLog;
        private boolean hasMany = true;

        @Builder.Default
        private ExecutionType executionType = ExecutionType.PROCESS_BUILDER;

        public String name() {
            return name;
        }

        public Path directory() {
            return directory;
        }

        public Path copyToArtifactPath() {
            return copyToArtifactPath;
        }
    }

    Map<String, DeployableMcpServer> deployableMcpServers;

    Path artifactCache = Paths.get(System.getProperty("user.home"), ".cache", "tool-gateway");

    String mcpStdioServersJsonLocation;

    String mcpHttpServersJsonLocation;

    boolean failOnMcpClientInit = false;

    boolean startMcpServerOnInitialize=true;

    Path killScript;

    boolean enableRedeployable = true;

    public boolean hasStdioServers() {
        return hasServerExisting(this.mcpStdioServersJsonLocation);
    }

    private static final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    private @NotNull Boolean hasServerExisting(String mcpStdioServersJsonLocation1) {

        return Optional.ofNullable(mcpStdioServersJsonLocation1)
                .map(s -> {
                    try {
                        return resolver.getResource(s).getFile().exists();
                    } catch (IOException e) {
                        log.error("Could not find resource {} with {}", s, e.getMessage(), e);
                        return false;
                    }
                })
                .orElse(false);
    }

    public boolean hasHttpServers() {
        return hasServerExisting(this.mcpHttpServersJsonLocation);
    }

}
