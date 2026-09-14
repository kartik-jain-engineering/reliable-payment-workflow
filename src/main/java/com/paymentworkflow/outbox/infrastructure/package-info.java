/**
 * Outbox module — infrastructure layer.
 *
 * <p>Adapters: JPA repositories and the (future) relay/publisher that reads
 * committed outbox rows. Depends inward on {@code domain} and
 * {@code application}.
 */
package com.paymentworkflow.outbox.infrastructure;
