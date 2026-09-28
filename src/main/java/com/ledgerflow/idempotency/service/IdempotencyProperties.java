package com.ledgerflow.idempotency.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledgerflow.idempotency")
public record IdempotencyProperties(int maxPollAttempts, Duration minPollBackoff, Duration maxPollBackoff) {
}
