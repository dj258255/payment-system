package com.beomsu.pay.reconciliation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 정산 대사 운영 어드민 — 사람 확인이 필요한 불일치(예외 큐)를 조회한다.
 *
 * <p>대사 엔진이 불일치 3분류(내부만/외부만/금액불일치)를 {@link ReconStatus#PENDING}으로 남기지만
 * 조회할 방법이 없었다. 이 어드민이 예외 큐를 관측하고, 사람이 확인한 건을 수기 확정하게 한다.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationAdminService {

    private final ReconciliationResultRepository repository;

    /** 사람 확인이 필요한 불일치(PENDING) 목록. */
    @Transactional(readOnly = true)
    public List<ReconMismatchView> listMismatches() {
        return repository.findByStatus(ReconStatus.PENDING).stream()
                .map(ReconciliationAdminService::toView)
                .toList();
    }

    /**
     * PENDING 대사 불일치 1건을 수기 확정한다(사람 확인 후).
     *
     * <p>OSIV off 환경이라 finder로 로드한 엔티티의 dirty-checking 변경은 자동 flush되지 않는다.
     * 따라서 상태 전이 후 반드시 {@link ReconciliationResultRepository#saveAndFlush(Object)}로
     * 명시 영속해야 변경이 DB에 남는다.
     */
    @Transactional
    public ReconMismatchView resolve(long id) {
        ReconciliationResult result = repository.findById(id)
                .orElseThrow(() -> new ReconciliationException("RECON_RESULT_NOT_FOUND",
                        "대사 결과를 찾을 수 없습니다: " + id));
        result.resolveManually();
        repository.saveAndFlush(result);
        return toView(result);
    }

    private static ReconMismatchView toView(ReconciliationResult r) {
        return new ReconMismatchView(r.getId(), r.getOrderNo(), r.getResult(),
                r.getInternalAmount(), r.getExternalAmount(), r.getReconciledAt());
    }
}
