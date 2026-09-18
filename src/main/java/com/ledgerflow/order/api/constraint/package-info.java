/**
 * Order module — custom Bean Validation constraints for the api layer.
 *
 * <p>Lives outside {@code domain} deliberately: the aggregate re-checks the
 * same rules in framework-free Java, so {@code jakarta.validation} never
 * needs to reach the domain.
 */
package com.ledgerflow.order.api.constraint;
