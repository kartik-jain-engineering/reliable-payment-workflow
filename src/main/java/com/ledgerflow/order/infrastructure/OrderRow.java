package com.ledgerflow.order.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.ledgerflow.order.domain.Order;
import com.ledgerflow.order.domain.OrderItem;
import com.ledgerflow.order.domain.OrderStatus;

/**
 * R2DBC row mapping for table {@code orders}.
 *
 * <p>Spring Data R2DBC has no aggregate support, so this type carries only
 * the columns of {@code orders}; the line items are a separate
 * {@link OrderItemRow} write driven by {@link OrderRepositoryAdapter}.
 *
 * <p>{@code status} is a {@code String} rather than an {@link OrderStatus}
 * on purpose: R2DBC reads a varchar into an enum out of the box but will not
 * write one back without a registered converter, and a plain string keeps
 * that converter out of the picture entirely.
 *
 * <p>Timestamps are {@link OffsetDateTime} pinned to UTC so they bind
 * straight to the {@code timestamptz} codec, with no implicit
 * system-default-zone conversion in between.
 */
@Table("orders")
class OrderRow {

    @Id
    private UUID id;

    @Column("customer_id")
    private String customerId;

    @Column("currency")
    private String currency;

    @Column("total_amount")
    private BigDecimal totalAmount;

    @Column("status")
    private String status;

    @Version
    @Column("version")
    private Long version;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    OrderRow() {
    }

    /**
     * Builds the row for an order that is not in the database yet.
     *
     * <p>{@code version} is seeded explicitly instead of being left null: the
     * column is {@code NOT NULL}, and seeding it keeps the first INSERT valid
     * regardless of how the template chooses to initialise a version property.
     */
    static OrderRow forInsert(Order order) {
        OrderRow row = new OrderRow();
        row.id = order.getId();
        row.customerId = order.getCustomerId();
        row.currency = order.getCurrency();
        row.totalAmount = order.getTotalAmount();
        row.status = order.getStatus().name();
        row.version = 0L;
        row.createdAt = toUtc(order.getCreatedAt());
        row.updatedAt = toUtc(order.getUpdatedAt());
        return row;
    }

    /**
     * Copies the mutable state of {@code order} onto an already-loaded row,
     * leaving {@code version} and {@code createdAt} as the database has them
     * so optimistic locking and the creation timestamp both survive.
     */
    OrderRow applyChanges(Order order) {
        this.status = order.getStatus().name();
        this.totalAmount = order.getTotalAmount();
        this.updatedAt = toUtc(order.getUpdatedAt());
        return this;
    }

    Order toDomain(List<OrderItem> items) {
        return Order.rehydrate(
                id,
                customerId,
                currency,
                totalAmount,
                OrderStatus.valueOf(status),
                items,
                createdAt.toInstant(),
                updatedAt.toInstant());
    }

    UUID getId() {
        return id;
    }

    Long getVersion() {
        return version;
    }

    private static OffsetDateTime toUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
