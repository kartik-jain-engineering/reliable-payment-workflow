package com.ledgerflow.idempotency.model.entity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.ledgerflow.idempotency.model.IdempotencyKeyStatus;

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
@Table("idempotency_keys")
public class IdempotencyKeyEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @NotBlank
    @Size(max = 255)
    @Column("owner_id")
    private String ownerId;

    @NotBlank
    @Size(max = 255)
    @Column("idempotency_key")
    private String idempotencyKey;

    @NotBlank
    @Size(max = 64)
    @Column("request_hash")
    private String requestHash;

    @NotNull
    @Column("status")
    private String statusCode;

    @Column("response_status")
    private Integer responseStatus;

    @Column("response_headers")
    private String responseHeaders;

    @Column("response_body")
    private String responseBody;

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


    public static IdempotencyKeyEntity claim(String ownerId, String idempotencyKey, String requestHash) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return IdempotencyKeyEntity.builder()
                .id(UUID.randomUUID())
                .ownerId(ownerId)
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .statusCode(IdempotencyKeyStatus.IN_PROGRESS.name())
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .isNew(true)
                .build();
    }


    public void complete(int responseStatus, String responseHeaders, String responseBody) {
        this.responseStatus = responseStatus;
        this.responseHeaders = responseHeaders;
        this.responseBody = responseBody;
        setStatus(IdempotencyKeyStatus.COMPLETED);
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public boolean matches(String requestHash) {
        return this.requestHash.equals(requestHash);
    }

    public IdempotencyKeyStatus getStatus() {
        return statusCode == null ? null : IdempotencyKeyStatus.valueOf(statusCode);
    }

    public void setStatus(IdempotencyKeyStatus status) {
        this.statusCode = status == null ? null : status.name();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
