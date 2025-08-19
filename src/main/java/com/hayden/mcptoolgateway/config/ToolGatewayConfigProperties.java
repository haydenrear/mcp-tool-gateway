package com.hayden.mcptoolgateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

@ConfigurationProperties(prefix = "gateway")
@Component
@Data
public class ToolGatewayConfigProperties {

    public record DeployableMcpServer(String name, String deployCommand, Path directory) {
    }

    Map<String, DeployableMcpServer> deployableMcpServers;

    boolean failOnMcpClientInit = false;

}
