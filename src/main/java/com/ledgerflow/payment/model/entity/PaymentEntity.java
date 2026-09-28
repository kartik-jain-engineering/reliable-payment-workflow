package com.ledgerflow.payment.model.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.ledgerflow.order.model.constraint.ValidCurrencyCode;
import com.ledgerflow.payment.model.InvalidStateTransitionException;
import com.ledgerflow.payment.model.PaymentStatus;

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
@Table("payments")
public class PaymentEntity implements Persistable<UUID> {

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS = allowedTransitions();

    @Id
    private UUID id;

    @NotNull
    @Column("order_id")
    private UUID orderId;

    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    @Column("amount")
    private BigDecimal amount;

    @NotNull
    @ValidCurrencyCode
    @Column("currency")
    private String currency;

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

    @Transient
    @Builder.Default
    private boolean isNew = false;


    public static PaymentEntity createNew(UUID orderId, BigDecimal amount, String currency) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return PaymentEntity.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .amount(amount)
                .currency(currency)
                .statusCode(PaymentStatus.PENDING.name())
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .isNew(true)
                .build();
    }


    public void transitionTo(PaymentStatus target) {
        PaymentStatus current = getStatus();
        if (target == null || !ALLOWED_TRANSITIONS.get(current).contains(target)) {
            throw new InvalidStateTransitionException(current, target);
        }
        setStatus(target);
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public PaymentStatus getStatus() {
        return statusCode == null ? null : PaymentStatus.valueOf(statusCode);
    }

    public void setStatus(PaymentStatus status) {
        this.statusCode = status == null ? null : status.name();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    private static Map<PaymentStatus, Set<PaymentStatus>> allowedTransitions() {
        Map<PaymentStatus, Set<PaymentStatus>> transitions = new EnumMap<>(PaymentStatus.class);
        transitions.put(PaymentStatus.PENDING,
                EnumSet.of(PaymentStatus.AUTHORIZED, PaymentStatus.DECLINED, PaymentStatus.TIMED_OUT));
        transitions.put(PaymentStatus.AUTHORIZED, EnumSet.noneOf(PaymentStatus.class));
        transitions.put(PaymentStatus.DECLINED, EnumSet.noneOf(PaymentStatus.class));
        transitions.put(PaymentStatus.TIMED_OUT, EnumSet.noneOf(PaymentStatus.class));
        return Map.copyOf(transitions);
    }
}
