package com.ledgerflow.order.model.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.ledgerflow.order.model.InvalidStateTransitionException;
import com.ledgerflow.order.model.OrderStatus;
import com.ledgerflow.order.model.constraint.ValidCurrencyCode;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("orders")
public class OrderEntity implements Persistable<UUID> {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = allowedTransitions();

    @Id
    private UUID id;

    @NotBlank
    @Column("customer_id")
    private String customerId;

    @NotNull
    @ValidCurrencyCode
    @Column("currency")
    private String currency;

    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    @Column("total_amount")
    private BigDecimal totalAmount;

    @NotNull
    @Column("status")
    private String statusCode;

    @Version
    @Column("version")
    private Long version;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @NotEmpty
    @Valid
    @Builder.Default
    @Transient
    @Setter(AccessLevel.NONE)
    private List<@NotNull OrderItemEntity> items = List.of();

    @Transient
    @Builder.Default
    private boolean isNew = false;


    public static OrderEntity createNew(
            String customerId, String currency, BigDecimal totalAmount, List<OrderItemEntity> items) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        List<OrderItemEntity> stampedItems = (items == null ? List.<OrderItemEntity>of() : items).stream()
                .map(item -> item.toBuilder().id(UUID.randomUUID()).orderId(id).isNew(true).build())
                .toList();

        return OrderEntity.builder()
                .id(id)
                .customerId(customerId)
                .currency(currency)
                .totalAmount(totalAmount)
                .statusCode(OrderStatus.CREATED.name())
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .items(stampedItems)
                .isNew(true)
                .build();
    }


    public void transitionTo(OrderStatus target) {
        OrderStatus current = getStatus();
        if (target == null || !ALLOWED_TRANSITIONS.get(current).contains(target)) {
            throw new InvalidStateTransitionException(current, target);
        }
        setStatus(target);
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void cancel() {
        transitionTo(OrderStatus.CANCELLED);
    }

    public OrderStatus getStatus() {
        return statusCode == null ? null : OrderStatus.valueOf(statusCode);
    }

    public void setStatus(OrderStatus status) {
        this.statusCode = status == null ? null : status.name();
    }


    public List<OrderItemEntity> getItems() {
        return items == null ? List.of() : items;
    }

    public void setItems(List<OrderItemEntity> items) {
        this.items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    private static Map<OrderStatus, Set<OrderStatus>> allowedTransitions() {
        Map<OrderStatus, Set<OrderStatus>> transitions = new EnumMap<>(OrderStatus.class);
        transitions.put(OrderStatus.CREATED, EnumSet.of(OrderStatus.PAYMENT_PENDING, OrderStatus.CANCELLED));
        transitions.put(OrderStatus.PAYMENT_PENDING,
                EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.FAILED, OrderStatus.CANCELLED));
        transitions.put(OrderStatus.CONFIRMED, EnumSet.noneOf(OrderStatus.class));
        transitions.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
        transitions.put(OrderStatus.FAILED, EnumSet.noneOf(OrderStatus.class));
        return Map.copyOf(transitions);
    }
}
