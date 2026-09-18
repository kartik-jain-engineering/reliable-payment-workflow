package com.ledgerflow.order.infrastructure;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.ledgerflow.order.domain.OrderItem;

/**
 * R2DBC row mapping for table {@code order_items}.
 *
 * <p>The domain value object has no identity, so the surrogate key here is a
 * pure persistence concern and — like the order id — is assigned in Java
 * rather than by the database. Rows are never updated in place:
 * {@link OrderRepositoryAdapter} deletes and re-inserts the whole set for an
 * order, so a fresh id per write is correct.
 */
@Table("order_items")
class OrderItemRow {

    @Id
    private UUID id;

    @Column("order_id")
    private UUID orderId;

    @Column("product_id")
    private String productId;

    @Column("quantity")
    private int quantity;

    @Column("unit_price")
    private BigDecimal unitPrice;

    OrderItemRow() {
    }

    static OrderItemRow forInsert(UUID orderId, OrderItem item) {
        OrderItemRow row = new OrderItemRow();
        row.id = UUID.randomUUID();
        row.orderId = orderId;
        row.productId = item.productId();
        row.quantity = item.quantity();
        row.unitPrice = item.unitPrice();
        return row;
    }

    OrderItem toDomain() {
        return new OrderItem(productId, quantity, unitPrice);
    }
}
