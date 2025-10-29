package com.hayden.mcptoolgateway.config;

import com.hayden.utilitymodule.security.KeyConfigProperties;
import com.hayden.utilitymodule.security.KeyFiles;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.endpoint.DefaultPasswordTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

import java.security.interfaces.RSAPublicKey;

@Configuration
@ConditionalOnProperty(value = "spring.ai.mcp.server.stdio", havingValue = "true")
@Import({KeyConfigProperties.class, KeyFiles.class})
public class StdioResourceConfig {

    @Bean
    DefaultPasswordTokenResponseClient passwordTokenResponseClient() {
        DefaultPasswordTokenResponseClient client = new DefaultPasswordTokenResponseClient();
        client.setRestOperations(new RestTemplate());
        return client;
    }

    @Bean
    ClientRegistration thisS2sClient() {
        return ClientRegistration.withRegistrationId("cdc")
                .build();
    }

    @Bean
    JwtDecoder decoderOnly(KeyFiles keyFiles) {
        return NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyFiles.getKeyPair().getPublic()).build();
    }

}
