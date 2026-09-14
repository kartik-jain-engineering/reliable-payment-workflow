package com.ledgerflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ledgerflow service.
 *
 * <p>The application is structured as a modular monolith: each business
 * capability lives in its own top-level package (e.g. {@code payment},
 * {@code ledger}, {@code idempotency}, {@code outbox}) with internal
 * {@code domain} / {@code application} / {@code infrastructure} layers.
 * Module boundaries are enforced by ArchUnit tests rather than by separate
 * Maven modules, keeping deployment simple while the codebase stays
 * decomposable.
 */
@SpringBootApplication
public class LedgerflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerflowApplication.class, args);
    }
}
