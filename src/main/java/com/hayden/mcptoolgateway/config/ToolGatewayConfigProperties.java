package com.hayden.mcptoolgateway.config;

import com.hayden.commitdiffmodel.codegen.types.ExecutionType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "gateway")
@Component
@Data
public class ToolGatewayConfigProperties {

    @Data
    public static class DeployableMcpServer {
        private String name;
        private String command;
        private String arguments;
        private Set<String> failurePatterns;
        private Set<String> successPatterns;
        private Path directory;
        private Path copyFromArtifactPath;
        private Path copyToArtifactPath;
        private ExecutionType executionType = ExecutionType.PROCESS_BUILDER;
    }

    Map<String, DeployableMcpServer> deployableMcpServers;

    Path artifactCache;

    String mcpServersJsonLocation;

    boolean failOnMcpClientInit = false;

    boolean startMcpServerOnInitialize=true;

}
