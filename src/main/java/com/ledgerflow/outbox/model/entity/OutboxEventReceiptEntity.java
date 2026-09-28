package com.ledgerflow.outbox.model.entity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
@Table("outbox_event_receipts")
public class OutboxEventReceiptEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @NotBlank
    @Size(max = 128)
    @Column("handler_name")
    private String handlerName;

    @NotNull
    @Column("event_id")
    private UUID eventId;

    @NotBlank
    @Size(max = 128)
    @Column("event_type")
    private String eventType;

    @NotNull
    @Column("aggregate_id")
    private UUID aggregateId;

    @Column("received_at")
    private OffsetDateTime receivedAt;

    @Transient
    @Builder.Default
    private boolean isNew = false;


    public static OutboxEventReceiptEntity of(String handlerName, OutboxEventEntity event) {
        return OutboxEventReceiptEntity.builder()
                .id(UUID.randomUUID())
                .handlerName(handlerName)
                .eventId(event.getId())
                .eventType(event.getEventType())
                .aggregateId(event.getAggregateId())
                .receivedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .isNew(true)
                .build();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
