package com.ledgerflow.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    @Bean
    LoggingSpanExporter loggingSpanExporter() {
        return LoggingSpanExporter.create();
    }
}
