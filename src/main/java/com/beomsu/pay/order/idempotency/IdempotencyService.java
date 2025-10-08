package com.beomsu.pay.order.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 멱등 실행기 — "따닥" 중복결제 방지 + 타임아웃 후 안전 재시도.
 *
 * <p>같은 멱등키의 요청은 딱 한 번만 실제 실행되고, 이후 재요청에는 <b>첫 응답을 그대로 재반환</b>한다.
 * 동시 요청은 {@code idempotency_keys}의 유니크 제약이 한 건만 통과시킨다 — INSERT 성공이 곧 처리권
 * 획득이라, 별도 분산락이 필요 없다.
 *
 * <p>이 메서드는 트랜잭션으로 감싸지 않는다. PROCESSING 레코드 삽입이 즉시 커밋돼야 동시 요청이
 * 그것을 보고 409로 막히기 때문이다. 실제 작업({@code action})은 자신의 트랜잭션을 갖는다.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public <T> T execute(String key, String apiPath, String httpMethod,
                         Object requestBody, Class<T> responseType, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            throw IdempotencyException.invalidKey("멱등키(Idempotency-Key)가 필요합니다.");
        }
        if (key.length() > 300) {
            throw IdempotencyException.invalidKey("멱등키는 300자를 넘을 수 없습니다.");
        }

        String requestHash = sha256(serialize(requestBody));

        // 1. 기존 레코드가 있으면 재반환/거절 판정
        Optional<IdempotencyRecord> existing =
                repository.findByIdempotencyKeyAndApiPathAndHttpMethod(key, apiPath, httpMethod);
        if (existing.isPresent()) {
            return handleExisting(existing.get(), requestHash, responseType);
        }

        // 2. 신규 — PROCESSING 삽입. 유니크 제약이 동시 요청 중 한 건만 통과시킨다.
        IdempotencyRecord record;
        try {
            record = repository.saveAndFlush(
                    IdempotencyRecord.start(key, apiPath, httpMethod, requestHash));
        } catch (DataIntegrityViolationException race) {
            // 다른 요청이 같은 순간 먼저 삽입함 → 그 레코드로 판정
            IdempotencyRecord other = repository
                    .findByIdempotencyKeyAndApiPathAndHttpMethod(key, apiPath, httpMethod)
                    .orElseThrow(() -> IdempotencyException.processing(key));
            return handleExisting(other, requestHash, responseType);
        }

        // 3. 실제 작업 실행 후 응답 저장
        try {
            T result = action.get();
            record.complete(serialize(result));
            repository.save(record);
            return result;
        } catch (RuntimeException e) {
            // 실패 시 레코드 제거 → 정상 재시도 가능(action은 트랜잭션이라 이미 롤백됨).
            repository.delete(record);
            throw e;
        }
    }

    private <T> T handleExisting(IdempotencyRecord record, String requestHash, Class<T> responseType) {
        if (!record.matches(requestHash)) {
            throw IdempotencyException.reused(record.getIdempotencyKey());   // 같은 키 + 다른 본문 → 422
        }
        if (!record.isDone()) {
            throw IdempotencyException.processing(record.getIdempotencyKey()); // 처리 중 → 409
        }
        return deserialize(record.getResponseBody(), responseType);            // 첫 응답 재반환
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등 처리 직렬화 실패", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("멱등 응답 역직렬화 실패", e);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
