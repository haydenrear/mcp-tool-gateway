package com.hayden.mcptoolgateway.config;

import com.hayden.commitdiffcontext.cdc_config.AuthorizationServerConfigProps;
import com.hayden.commitdiffcontext.cdc_config.CreditsConfig;
import com.hayden.commitdiffcontext.credits.CreditVerificationFilter;
import com.hayden.mcptoolgateway.kubernetes.KubernetesFilter;
import com.hayden.utilitymodule.security.KeyConfigProperties;
import com.hayden.utilitymodule.security.KeyFiles;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

@Configuration
@Import({
        CreditsConfig.class, KeyConfigProperties.class, KeyFiles.class,
        CreditVerificationFilter.class, AuthorizationServerConfigProps.class})
public class ResourceServerConfig {

    @Bean
    SecurityFilterChain resourceServerSecurityConf(HttpSecurity http, KubernetesFilter kubernetesFilter, CreditVerificationFilter creditVerificationFilter) throws Exception {
        var b= http
                .oauth2ResourceServer(res -> res.jwt(Customizer.withDefaults()))
                .addFilterAfter(creditVerificationFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(kubernetesFilter, CreditVerificationFilter.class)
                .authorizeHttpRequests(authorizeRequests -> authorizeRequests.anyRequest().authenticated())
                .csrf(CsrfConfigurer::disable)
                .cors(Customizer.withDefaults())
                .build();

        return b;
    }

    @Bean
    ClientCredentialsOAuth2AuthorizedClientProvider clientCredentialsOAuth2AuthorizedClientProvider() {
        var c = new ClientCredentialsOAuth2AuthorizedClientProvider();
        return c;
    }

    @Bean
    JwtDecoder jwtDecoder(KeyFiles keyFiles) {
        var keyPair = keyFiles.getKeyPair();
        return NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
    }

    @Bean
    NimbusJwtEncoder jwtEncoder(KeyFiles keyFiles) {
        return new NimbusJwtEncoder(jwkSource(keyFiles));
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(KeyFiles keyFiles) {
        KeyPair keyPair = keyFiles.getKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }
}
