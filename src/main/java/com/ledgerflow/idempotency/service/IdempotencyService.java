package com.ledgerflow.idempotency.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.idempotency.model.IdempotencyKeyStatus;
import com.ledgerflow.idempotency.model.entity.IdempotencyKeyEntity;
import com.ledgerflow.idempotency.repo.IdempotencyKeyRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final TypeReference<Map<String, List<String>>> HEADERS_TYPE = new TypeReference<>() {
    };

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final IdempotencyProperties properties;

    public <T> Mono<ResponseEntity<T>> execute(
            String ownerId,
            String idempotencyKey,
            Object request,
            Class<T> responseType,
            Supplier<Mono<ResponseEntity<T>>> action) {
        return Mono.fromCallable(() -> IdempotencyKeyEntity.claim(ownerId, idempotencyKey, hash(request)))
                .flatMap(claim -> tryClaim(claim)
                        .flatMap(won -> won
                                ? perform(claim.getId(), action)
                                : replay(ownerId, idempotencyKey, claim.getRequestHash(), responseType)));
    }

    private Mono<Boolean> tryClaim(IdempotencyKeyEntity claim) {
        return validate(claim)
                .then(Mono.defer(() -> idempotencyKeyRepository.save(claim)))
                .thenReturn(true)
                .onErrorResume(DuplicateKeyException.class, ex -> Mono.just(false));
    }

    private <T> Mono<ResponseEntity<T>> perform(UUID claimId, Supplier<Mono<ResponseEntity<T>>> action) {
        return Mono.defer(action)
                .onErrorResume(ex -> idempotencyKeyRepository.deleteById(claimId)
                        .doOnError(ex::addSuppressed)
                        .onErrorComplete()
                        .then(Mono.<ResponseEntity<T>>error(ex)))
                .flatMap(response -> complete(claimId, response).thenReturn(response));
    }

    private Mono<IdempotencyKeyEntity> complete(UUID claimId, ResponseEntity<?> response) {
        return idempotencyKeyRepository.findById(claimId)
                .flatMap(claim -> Mono.fromCallable(() -> {
                    claim.complete(
                            response.getStatusCode().value(),
                            objectMapper.writeValueAsString(response.getHeaders()),
                            objectMapper.writeValueAsString(response.getBody()));
                    return claim;
                }))
                .flatMap(claim -> validate(claim).then(Mono.defer(() -> idempotencyKeyRepository.save(claim))));
    }

    private <T> Mono<ResponseEntity<T>> replay(
            String ownerId, String idempotencyKey, String requestHash, Class<T> responseType) {
        return idempotencyKeyRepository.findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey)
                .switchIfEmpty(Mono.error(IdempotencyKeyInProgressException::new))
                .flatMap(stored -> toReplay(stored, requestHash, responseType))
                .retryWhen(Retry.backoff(properties.maxPollAttempts(), properties.minPollBackoff())
                        .maxBackoff(properties.maxPollBackoff())
                        .filter(IdempotencyKeyInProgressException.class::isInstance)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    private <T> Mono<ResponseEntity<T>> toReplay(
            IdempotencyKeyEntity stored, String requestHash, Class<T> responseType) {
        if (!stored.matches(requestHash)) {
            return Mono.error(new IdempotencyKeyReusedException());
        }
        if (stored.getStatus() != IdempotencyKeyStatus.COMPLETED) {
            return Mono.error(new IdempotencyKeyInProgressException());
        }
        return Mono.fromCallable(() -> {
            Map<String, List<String>> headers = objectMapper.readValue(stored.getResponseHeaders(), HEADERS_TYPE);
            T body = objectMapper.readValue(stored.getResponseBody(), responseType);
            return ResponseEntity.status(stored.getResponseStatus())
                    .headers(replayed -> replayed.putAll(headers))
                    .body(body);
        });
    }

    private String hash(Object request) throws JsonProcessingException, NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(request));
        return HexFormat.of().formatHex(digest);
    }

    private Mono<Void> validate(IdempotencyKeyEntity claim) {
        Set<ConstraintViolation<IdempotencyKeyEntity>> violations = validator.validate(claim);
        if (violations.isEmpty()) {
            return Mono.empty();
        }
        return Mono.error(new ConstraintViolationException(violations));
    }
}
