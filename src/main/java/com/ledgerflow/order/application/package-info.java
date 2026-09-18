/**
 * Order module — application layer.
 *
 * <p>Use cases that orchestrate the {@code domain} aggregate and its
 * persistence port: load, mutate, save, and translate "not found" into a
 * domain exception. Holds no business rules of its own — those belong to
 * the aggregate.
 */
package com.ledgerflow.order.application;
