package com.ledgerflow.order.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit tests for the {@link OrderItem} value object's compact
 * constructor invariants — the domain's defence-in-depth layer, independent
 * of the DTO-level Bean Validation rules.
 */
class OrderItemTest {

    @Test
    void constructor_shouldPreserveFields_onValidInput() {
        OrderItem item = new OrderItem("sku-1", 3, new BigDecimal("2.50"));

        assertThat(item.productId()).isEqualTo("sku-1");
        assertThat(item.quantity()).isEqualTo(3);
        assertThat(item.unitPrice()).isEqualByComparingTo("2.50");
    }

    @Test
    void constructor_shouldAllowZeroUnitPrice() {
        OrderItem item = new OrderItem("sku-1", 1, BigDecimal.ZERO);

        assertThat(item.unitPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void constructor_shouldThrow_whenProductIdIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrderItem(null, 1, BigDecimal.ONE));
    }

    @Test
    void constructor_shouldThrow_whenProductIdIsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrderItem("   ", 1, BigDecimal.ONE));
    }

    @Test
    void constructor_shouldThrow_whenQuantityIsZero() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrderItem("sku-1", 0, BigDecimal.ONE));
    }

    @Test
    void constructor_shouldThrow_whenQuantityIsNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrderItem("sku-1", -1, BigDecimal.ONE));
    }

    @Test
    void constructor_shouldThrow_whenUnitPriceIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrderItem("sku-1", 1, null));
    }

    @Test
    void constructor_shouldThrow_whenUnitPriceIsNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrderItem("sku-1", 1, new BigDecimal("-0.01")));
    }
}
