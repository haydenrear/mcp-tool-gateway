package com.hayden.mcptoolgateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class McpToolGatewayApplicationTests {

    @Test
    void contextLoads() {
    }

    @Test
    void containsTestJar() {
        assertThat(new File("build/libs/test-mcp-server.jar").exists()).isTrue();
    }

}
