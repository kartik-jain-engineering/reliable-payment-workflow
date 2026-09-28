package com.ledgerflow.order.model.entity;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Table("order_items")
public class OrderItemEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("order_id")
    private UUID orderId;

    @NotBlank
    @Column("product_id")
    private String productId;

    @Positive
    @Column("quantity")
    private int quantity;

    @NotNull
    @PositiveOrZero
    @Column("unit_price")
    private BigDecimal unitPrice;

    @Transient
    @Builder.Default
    private boolean isNew = false;


    public static OrderItemEntity forInsert(UUID orderId, String productId, int quantity, BigDecimal unitPrice) {
        return OrderItemEntity.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .isNew(true)
                .build();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
