package com.beomsu.pay.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    @DisplayName("resolve: PENDING을 MANUALLY_RESOLVED로 전이하고 saveAndFlush로 명시 영속한다")
    void resolveTransitionsAndPersists() {
        ReconciliationResult mismatch = ReconciliationResult.amountMismatch("ord-1", 10_000, 9_000);
        when(repository.findById(7L)).thenReturn(Optional.of(mismatch));
        when(repository.saveAndFlush(mismatch)).thenReturn(mismatch);

        ReconMismatchView view = service.resolve(7L);

        assertThat(mismatch.getStatus()).isEqualTo(ReconStatus.MANUALLY_RESOLVED);
        assertThat(view.orderNo()).isEqualTo("ord-1");
        // OSIV off — 상태 변경이 DB에 남으려면 saveAndFlush가 반드시 불려야 한다.
        verify(repository).saveAndFlush(mismatch);
    }

    @Test
    @DisplayName("resolve: 이미 확정된(PENDING 아님) 건은 예외 — saveAndFlush도 하지 않는다")
    void resolveRejectsNonPending() {
        ReconciliationResult matched = ReconciliationResult.matched("ord-2", 10_000); // AUTO_RESOLVED
        when(repository.findById(8L)).thenReturn(Optional.of(matched));

        assertThatThrownBy(() -> service.resolve(8L))
                .isInstanceOf(ReconciliationException.class)
                .satisfies(e -> assertThat(((ReconciliationException) e).code())
                        .isEqualTo("INVALID_STATE_TRANSITION"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("resolve: 없는 id면 예외")
    void resolveThrowsWhenNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(404L))
                .isInstanceOf(ReconciliationException.class)
                .satisfies(e -> assertThat(((ReconciliationException) e).code())
                        .isEqualTo("RECON_RESULT_NOT_FOUND"));
        verify(repository, never()).saveAndFlush(any());
    }
}
