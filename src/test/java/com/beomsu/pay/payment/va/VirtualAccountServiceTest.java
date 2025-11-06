package com.beomsu.pay.payment.va;

import com.beomsu.pay.payment.pg.PgClient;
import com.beomsu.pay.payment.pg.PgPaymentStatus;
import com.beomsu.pay.payment.pg.PgQueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VirtualAccountServiceTest {

    private VirtualAccountRepository repository;
    private PgClient pg;
    private VirtualAccountService service;

    @BeforeEach
    void setUp() {
        repository = mock(VirtualAccountRepository.class);
        pg = mock(PgClient.class);
        service = new VirtualAccountService(repository, pg);
    }

    private VirtualAccount waiting(String paymentKey) {
        return VirtualAccount.issue("order-1", paymentKey, "20", "12345678901234",
                10_000L, Instant.now().plusSeconds(3600));
    }

    @Test
    @DisplayName("confirmDeposit: 조회가 APPROVED면 DONE으로 확정")
    void confirmDepositWhenPgApproved() {
        VirtualAccount va = waiting("pk-1");
        when(repository.findByPaymentKey("pk-1")).thenReturn(Optional.of(va));
        when(pg.query("pk-1")).thenReturn(new PgQueryResult(PgPaymentStatus.APPROVED, "VIRTUAL_ACCOUNT"));

        service.confirmDeposit("pk-1");

        assertThat(va.getStatus()).isEqualTo(VaStatus.DONE);
        assertThat(va.getDepositedAt()).isNotNull();
    }

    @Test
    @DisplayName("confirmDeposit: 조회가 NOT_FOUND면 상태 불변(웹훅 무시)")
    void confirmDepositIgnoredWhenPgNotFound() {
        VirtualAccount va = waiting("pk-2");
        when(repository.findByPaymentKey("pk-2")).thenReturn(Optional.of(va));
        when(pg.query("pk-2")).thenReturn(new PgQueryResult(PgPaymentStatus.NOT_FOUND, null));

        service.confirmDeposit("pk-2");

        assertThat(va.getStatus()).isEqualTo(VaStatus.WAITING_FOR_DEPOSIT);
        assertThat(va.getDepositedAt()).isNull();
    }

    @Test
    @DisplayName("expireOverdue: 만료 대상이 조회 NOT_FOUND면 EXPIRED")
    void expireOverdueWhenPgNotFound() {
        VirtualAccount va = waiting("pk-3");
        when(repository.findByStatusAndDueDateBefore(eq(VaStatus.WAITING_FOR_DEPOSIT), any(Instant.class)))
                .thenReturn(List.of(va));
        when(pg.query("pk-3")).thenReturn(new PgQueryResult(PgPaymentStatus.NOT_FOUND, null));

        int processed = service.expireOverdue(Instant.now());

        assertThat(processed).isEqualTo(1);
        assertThat(va.getStatus()).isEqualTo(VaStatus.EXPIRED);
    }

    @Test
    @DisplayName("expireOverdue: 만료 대상인데 조회 APPROVED면(레이스) EXPIRED 아니라 DONE")
    void expireOverdueRaceResolvesToDone() {
        VirtualAccount va = waiting("pk-4");
        when(repository.findByStatusAndDueDateBefore(eq(VaStatus.WAITING_FOR_DEPOSIT), any(Instant.class)))
                .thenReturn(List.of(va));
        when(pg.query("pk-4")).thenReturn(new PgQueryResult(PgPaymentStatus.APPROVED, "VIRTUAL_ACCOUNT"));

        int processed = service.expireOverdue(Instant.now());

        assertThat(processed).isEqualTo(1);
        assertThat(va.getStatus()).isEqualTo(VaStatus.DONE);
        assertThat(va.getDepositedAt()).isNotNull();
    }

    @Test
    @DisplayName("handleDepositReversal: DONE인 가상계좌를 WAITING_FOR_DEPOSIT로 역전이")
    void handleDepositReversal() {
        VirtualAccount va = waiting("pk-5");
        va.confirmDeposit();
        when(repository.findByPaymentKey("pk-5")).thenReturn(Optional.of(va));

        service.handleDepositReversal("pk-5", "은행 입금 실패 지연 통보");

        assertThat(va.getStatus()).isEqualTo(VaStatus.WAITING_FOR_DEPOSIT);
        assertThat(va.getDepositedAt()).isNull();
    }
}
