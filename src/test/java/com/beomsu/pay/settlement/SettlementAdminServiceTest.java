package com.beomsu.pay.settlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SettlementAdminServiceTest {

    private SettlementRepository repository;
    private SettlementService settlementService;
    private SettlementAdminService service;

    private static final LocalDate DATE = LocalDate.of(2026, 7, 5);
    private static final LocalDate PAYOUT = LocalDate.of(2026, 7, 7);

    @BeforeEach
    void setUp() {
        repository = mock(SettlementRepository.class);
        settlementService = mock(SettlementService.class);
        service = new SettlementAdminService(repository, settlementService);
    }

    @Test
    @DisplayName("list: 정산을 페이지 뷰 record로 매핑한다")
    void listMapsToView() {
        Settlement s = Settlement.of(DATE, 100_000, 2_700, 270, 3, PAYOUT);
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(s), pageable, 1));

        Page<SettlementView> views = service.list(pageable);

        assertThat(views).hasSize(1);
        SettlementView v = views.getContent().get(0);
        assertThat(v.grossAmount()).isEqualTo(100_000);
        assertThat(v.feeAmount()).isEqualTo(2_700);
        assertThat(v.feeVatAmount()).isEqualTo(270);
        assertThat(v.netAmount()).isEqualTo(97_030);
        assertThat(v.payoutDate()).isEqualTo(PAYOUT);
        assertThat(v.status()).isEqualTo(SettlementStatus.CREATED);
        verify(repository).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("confirmPayout: CREATED → PAID_OUT 전이하고 saveAndFlush로 명시 영속")
    void confirmPayoutTransitionsAndPersists() {
        Settlement s = Settlement.of(DATE, 100_000, 2_700, 270, 3, PAYOUT);
        when(repository.findById(7L)).thenReturn(Optional.of(s));
        when(repository.saveAndFlush(s)).thenReturn(s);

        SettlementView view = service.confirmPayout(7L);

        assertThat(s.getStatus()).isEqualTo(SettlementStatus.PAID_OUT);
        assertThat(view.status()).isEqualTo(SettlementStatus.PAID_OUT);
        assertThat(view.paidOutAt()).isNotNull();
        // OSIV off — 상태 변경이 DB에 남으려면 saveAndFlush가 반드시 불려야 한다.
        verify(repository).saveAndFlush(s);
    }

    @Test
    @DisplayName("confirmPayout: 없는 id면 SETTLEMENT_NOT_FOUND 예외, saveAndFlush 안 함")
    void confirmPayoutThrowsWhenNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmPayout(404L))
                .isInstanceOf(SettlementException.class)
                .satisfies(e -> assertThat(((SettlementException) e).code())
                        .isEqualTo("SETTLEMENT_NOT_FOUND"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("runSettlement: settle에 위임한다")
    void runSettlementDelegates() {
        Settlement s = Settlement.of(DATE, 100_000, 2_700, 270, 3, PAYOUT);
        when(settlementService.settle(DATE)).thenReturn(s);

        Settlement result = service.runSettlement(DATE);

        assertThat(result).isSameAs(s);
        verify(settlementService).settle(DATE);
    }

    @Test
    @DisplayName("runSettlement: 재실행/대상없음이면 null 그대로 반환")
    void runSettlementReturnsNullWhenNothingCreated() {
        when(settlementService.settle(DATE)).thenReturn(null);

        assertThat(service.runSettlement(DATE)).isNull();
    }
}
