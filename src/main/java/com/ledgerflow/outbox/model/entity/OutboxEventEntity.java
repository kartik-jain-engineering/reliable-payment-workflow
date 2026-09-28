package com.ledgerflow.outbox.model.entity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.ledgerflow.outbox.model.OutboxEventStatus;
import com.ledgerflow.outbox.model.event.DomainEvent;

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
@Table("outbox_events")
public class OutboxEventEntity implements Persistable<UUID> {

    public static final int LAST_ERROR_MAX_LENGTH = 1000;

    @Id
    private UUID id;

    @NotBlank
    @Size(max = 64)
    @Column("aggregate_type")
    private String aggregateType;

    @NotNull
    @Column("aggregate_id")
    private UUID aggregateId;

    @NotBlank
    @Size(max = 128)
    @Column("event_type")
    private String eventType;

    @Column("event_version")
    private int eventVersion;

    @NotBlank
    @Column("payload")
    private String payload;

    @NotNull
    @Column("status")
    private String statusCode;

    @PositiveOrZero
    @Column("attempts")
    private int attempts;

    @Size(max = LAST_ERROR_MAX_LENGTH)
    @Column("last_error")
    private String lastError;

    @Version
    @Column("version")
    private Long version;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("published_at")
    private OffsetDateTime publishedAt;

    @Transient
    @Builder.Default
    private boolean isNew = false;


    public static OutboxEventEntity pending(DomainEvent event, String payload) {
        return OutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .aggregateType(event.aggregateType())
                .aggregateId(event.aggregateId())
                .eventType(event.eventType())
                .eventVersion(event.schemaVersion())
                .payload(payload)
                .statusCode(OutboxEventStatus.PENDING.name())
                .attempts(0)
                .version(0L)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .isNew(true)
                .build();
    }


    public void markPublished() {
        setStatus(OutboxEventStatus.PUBLISHED);
        this.publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void recordFailedAttempt(String error, int maxAttempts) {
        this.attempts++;
        this.lastError = error == null || error.length() <= LAST_ERROR_MAX_LENGTH
                ? error
                : error.substring(0, LAST_ERROR_MAX_LENGTH);
        if (attempts >= maxAttempts) {
            setStatus(OutboxEventStatus.FAILED);
        }
    }

    public OutboxEventStatus getStatus() {
        return statusCode == null ? null : OutboxEventStatus.valueOf(statusCode);
    }

    public void setStatus(OutboxEventStatus status) {
        this.statusCode = status == null ? null : status.name();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
