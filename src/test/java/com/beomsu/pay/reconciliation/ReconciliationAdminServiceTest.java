package com.beomsu.pay.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReconciliationAdminServiceTest {

    private ReconciliationResultRepository repository;
    private ReconciliationAdminService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReconciliationResultRepository.class);
        service = new ReconciliationAdminService(repository);
    }

    @Test
    @DisplayName("listMismatches: PENDING 예외 큐만 조회해 뷰로 매핑한다")
    void listMismatchesMapsPendingToView() {
        ReconciliationResult mismatch = ReconciliationResult.amountMismatch("ord-1", 10_000, 9_000);
        when(repository.findByStatus(ReconStatus.PENDING)).thenReturn(List.of(mismatch));

        List<ReconMismatchView> views = service.listMismatches();

        assertThat(views).hasSize(1);
        ReconMismatchView v = views.get(0);
        assertThat(v.orderNo()).isEqualTo("ord-1");
        assertThat(v.result()).isEqualTo(ReconResultType.AMOUNT_MISMATCH);
        assertThat(v.internalAmount()).isEqualTo(10_000);
        assertThat(v.externalAmount()).isEqualTo(9_000);
        // 조회는 PENDING 상태만 대상으로 한다(AUTO_RESOLVED는 제외).
        verify(repository).findByStatus(ReconStatus.PENDING);
        verify(repository, never()).findByStatus(ReconStatus.AUTO_RESOLVED);
    }
}
