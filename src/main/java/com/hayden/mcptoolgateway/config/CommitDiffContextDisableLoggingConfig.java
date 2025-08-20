package com.hayden.mcptoolgateway.config;

import com.hayden.tracing.config.DisableTelemetryLoggingConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

@Import(DisableTelemetryLoggingConfig.class)
@Profile("!telemetry-logging")
@Configuration
public class CommitDiffContextDisableLoggingConfig { }
