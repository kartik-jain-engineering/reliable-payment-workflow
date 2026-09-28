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
@Table("outbox_consumed_events")
public class OutboxConsumedEventEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @NotBlank
    @Size(max = 128)
    @Column("handler_name")
    private String handlerName;

    @NotNull
    @Column("event_id")
    private UUID eventId;

    @Column("consumed_at")
    private OffsetDateTime consumedAt;

    @Transient
    @Builder.Default
    private boolean isNew = false;


    public static OutboxConsumedEventEntity claim(String handlerName, UUID eventId) {
        return OutboxConsumedEventEntity.builder()
                .id(UUID.randomUUID())
                .handlerName(handlerName)
                .eventId(eventId)
                .consumedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .isNew(true)
                .build();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
