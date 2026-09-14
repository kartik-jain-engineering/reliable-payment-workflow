/**
 * Idempotency module — domain layer.
 *
 * <p>Models idempotency keys and the rules for detecting duplicate
 * payment requests. Must not depend on {@code application} or
 * {@code infrastructure} packages of any module, or on Spring/persistence/
 * web frameworks.
 */
package com.ledgerflow.idempotency.domain;
