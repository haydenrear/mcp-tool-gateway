package com.hayden.mcptoolgateway.tool;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;

@Component
@RequiredArgsConstructor
public class TestUtil {


    public static void writeToCopyTo(byte[] bytes, ToolGatewayConfigProperties.DecoratedMcpServer testServer1) throws IOException {
        Files.write(testServer1.copyToArtifactPath().resolve(testServer1.getCopyFromArtifactPath().toFile().getName()), bytes);
    }
}
