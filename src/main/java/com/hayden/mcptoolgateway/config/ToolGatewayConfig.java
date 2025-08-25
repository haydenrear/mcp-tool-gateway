package com.hayden.mcptoolgateway.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.mcptoolgateway.fn.RedeployFunction;
import com.hayden.utilitymodule.stream.StreamUtil;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.autoconfigure.configurer.McpSyncClientConfigurer;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.mcp.customizer.McpSyncClientCustomizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Configuration
public class ToolGatewayConfig {

    @Autowired
    private ToolGatewayConfigProperties toolGatewayConfigProperties;

    private Map<String, ServerParameters> resourceToServerParameters() {
        try {
            Map<String, Map<String, McpStdioClientProperties.Parameters>> stdioConnection = new ObjectMapper().readValue(
                    new PathMatchingResourcePatternResolver().getResource(toolGatewayConfigProperties.mcpServersJsonLocation)
                                                             .getFile(),
                    new TypeReference<>() {});

            Map<String, McpStdioClientProperties.Parameters> mcpServerJsonConfig = stdioConnection.entrySet().iterator().next().getValue();

            return mcpServerJsonConfig.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, kv -> {
                McpStdioClientProperties.Parameters parameters = kv.getValue();
                return ServerParameters.builder(parameters.command())
                                       .args(parameters.args())
                                       .env(parameters.env())
                                       .build();
            }));
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to read stdio connection resource", e);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public McpSyncClientConfigurer mcpSyncClientConfigurer(@Autowired(required = false) List<McpSyncClientCustomizer> customizers) {
        if (customizers == null) {
            customizers = new ArrayList<>();
        }
        return new McpSyncClientConfigurer(customizers);
    }

    @Bean
    @Primary
    public List<NamedClientMcpTransport> namedTransports() {
        return resourceToServerParameters().entrySet()
                .stream()
                .map(e -> new NamedClientMcpTransport(e.getKey(), new StdioClientTransport(e.getValue())))
                .toList();
    }

    @SneakyThrows
    @Bean
    @Profile({"rollback-tests"})
    public StdioServerTransportProvider transportProvider(ObjectMapper objectMapper,
                                                          WritableInput writableInput) {

        return new StdioServerTransportProvider(objectMapper, writableInput.input(), new OutputStream() {
            @Override
            public void write(int b) {
                System.out.print((char) b);
            }
        });
    }

    @Bean
    public CommandLineRunner initializeCodeExecutions(ToolGatewayConfigProperties props,
                                                      RedeployFunction graphqlRedeploy) {
        StreamUtil.toStream(props.deployableMcpServers.values())
                .forEach(graphqlRedeploy::register);
        return args -> {};
    }

    @Bean
    public CommandLineRunner killAnyExistingCdc(ToolGatewayConfigProperties props) {
        if (props.getKillScript().toFile().exists()) {
            log.info("Found kill script in properties.");
            doKill(props.getKillScript().toFile());
        } else {
            var f = new File("");
            if (!f.toPath().resolve("kill-cdc.sh").toFile().exists()
                    && f.toPath().getParent().resolve("kill-cdc.sh").toFile().exists()) {
                f = f.toPath().getParent().resolve("kill-cdc.sh").toFile();
            } else if (f.toPath().resolve("kill-cdc.sh").toFile().exists()) {
                f = f.toPath().resolve("kill-cdc.sh").toFile();
            } else if (f.toPath().resolve("kill-cdc.sh").toFile().exists()) {

            }
            if (f.exists()) {
                doKill(f);
            }
        }

        return args -> {};
    }

    private static void doKill(File f) {
        try {
            log.info("Killing cdc.");
            Process bash = new ProcessBuilder("bash", f.getAbsolutePath())
                    .start();
            bash.waitFor();
            try(var b = new BufferedReader(new InputStreamReader(bash.getInputStream()))) {
                var l = b.lines().collect(Collectors.joining(System.lineSeparator())) ;
                log.info("Attempted to stop existing:\n{}", l);
            }
        } catch (InterruptedException | IOException e) {
           log.error("Failed to kill existing...");
        }
    }

}
