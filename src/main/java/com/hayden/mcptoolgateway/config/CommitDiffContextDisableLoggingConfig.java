package com.hayden.mcptoolgateway.config;

import com.hayden.tracing.config.DisableTelemetryLoggingConfig;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

@Import(DisableTelemetryLoggingConfig.class)
@Profile("!telemetry-logging")
@Configuration
@EnableAutoConfiguration(exclude = { SessionAutoConfiguration.class })
public class CommitDiffContextDisableLoggingConfig { }
