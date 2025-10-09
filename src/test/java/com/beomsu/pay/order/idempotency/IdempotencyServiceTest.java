package com.beomsu.pay.order.idempotency;

import com.beomsu.pay.order.CheckoutResult;
import com.beomsu.pay.order.OrderStatus;
import com.beomsu.pay.payment.PaymentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest {

    private static final String KEY = "idem-key-1";
    private static final String PATH = "/api/v1/payments/confirm";
    private static final Map<String, Object> REQUEST = Map.of("orderNo", "o1", "amount", 20_000);

    private IdempotencyRepository repository;
    private ObjectMapper mapper;
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        repository = mock(IdempotencyRepository.class);
        mapper = new ObjectMapper().findAndRegisterModules();
        service = new IdempotencyService(repository, mapper);
    }

    private CheckoutResult cached() {
        return new CheckoutResult("o1", OrderStatus.PAID, PaymentStatus.DONE, "승인 완료");
    }

    private Supplier<CheckoutResult> actionReturning(CheckoutResult r, AtomicInteger calls) {
        return () -> { calls.incrementAndGet(); return r; };
    }

    private String hashOf(Object req) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsString(req).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(h);
    }

    @Test
    @DisplayName("신규 키: action을 실행하고 응답을 DONE으로 저장한다")
    void newKeyExecutesAndStores() {
        AtomicInteger calls = new AtomicInteger();
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        CheckoutResult result = service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class,
                actionReturning(cached(), calls));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
        verify(repository).save(argThat(r -> r.isDone()));
    }

    @Test
    @DisplayName("완료된 같은 키: action을 다시 실행하지 않고 첫 응답을 재반환한다 (따닥 방지)")
    void doneKeyReplaysWithoutExecuting() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IdempotencyRecord done = IdempotencyRecord.start(KEY, PATH, "POST", hashOf(REQUEST));
        done.complete(mapper.writeValueAsString(cached()));
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.of(done));

        CheckoutResult result = service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class,
                actionReturning(cached(), calls));

        assertThat(calls.get()).isZero();                     // 실제 결제 재실행 없음
        assertThat(result.orderNo()).isEqualTo("o1");
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("처리 중인 같은 키: 409 IDEMPOTENT_REQUEST_PROCESSING")
    void processingKeyRejectsWith409() throws Exception {
        IdempotencyRecord processing = IdempotencyRecord.start(KEY, PATH, "POST", hashOf(REQUEST));
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.of(processing));

        assertThatThrownBy(() -> service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class,
                actionReturning(cached(), new AtomicInteger())))
                .isInstanceOf(IdempotencyException.class)
                .satisfies(e -> assertThat(((IdempotencyException) e).code())
                        .isEqualTo("IDEMPOTENT_REQUEST_PROCESSING"));
    }

    @Test
    @DisplayName("같은 키 + 다른 본문: 422 IDEMPOTENCY_KEY_REUSED")
    void sameKeyDifferentBodyRejectsWith422() {
        IdempotencyRecord other = IdempotencyRecord.start(KEY, PATH, "POST", "다른요청의해시");
        other.complete("{}");
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class,
                actionReturning(cached(), new AtomicInteger())))
                .isInstanceOf(IdempotencyException.class)
                .satisfies(e -> assertThat(((IdempotencyException) e).code())
                        .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @DisplayName("멱등키 누락: 400 INVALID_IDEMPOTENCY_KEY")
    void missingKeyRejectsWith400() {
        assertThatThrownBy(() -> service.execute("  ", PATH, "POST", REQUEST, CheckoutResult.class,
                actionReturning(cached(), new AtomicInteger())))
                .isInstanceOf(IdempotencyException.class)
                .satisfies(e -> assertThat(((IdempotencyException) e).code())
                        .isEqualTo("INVALID_IDEMPOTENCY_KEY"));
    }
}
