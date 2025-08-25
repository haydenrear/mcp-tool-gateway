package com.hayden.mcptoolgateway.config;

import com.hayden.commitdiffmodel.codegen.types.ExecutionType;
import lombok.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

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

    String mcpServersJsonLocation;

    boolean failOnMcpClientInit = false;

    boolean startMcpServerOnInitialize=true;

    Path killScript;

}
