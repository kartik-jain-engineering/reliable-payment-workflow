package com.ledgerflow.outbox.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledgerflow.outbox")
public record OutboxProperties(Publisher publisher) {

    public record Publisher(boolean enabled, Duration pollInterval, int batchSize, int maxAttempts) {
    }
}
