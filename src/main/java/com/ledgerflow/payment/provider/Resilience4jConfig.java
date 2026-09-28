package com.ledgerflow.payment.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;

@Configuration(proxyBeanMethods = false)
public class Resilience4jConfig {

    public static final String PAYMENT_PROVIDER = "paymentProvider";

    @Bean
    TimeLimiter paymentProviderTimeLimiter(TimeLimiterRegistry registry) {
        return registry.timeLimiter(PAYMENT_PROVIDER);
    }

    @Bean
    Retry paymentProviderRetry(RetryRegistry registry) {
        return registry.retry(PAYMENT_PROVIDER);
    }

    @Bean
    CircuitBreaker paymentProviderCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(PAYMENT_PROVIDER);
    }
}
