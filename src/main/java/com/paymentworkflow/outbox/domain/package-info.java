/**
 * Outbox module — domain layer.
 *
 * <p>Models the transactional outbox entries used to publish reliable
 * events as a side effect of committed database transactions, without a
 * message broker. Must not depend on {@code application} or
 * {@code infrastructure} packages of any module, or on Spring/persistence/
 * web frameworks.
 */
package com.paymentworkflow.outbox.domain;
