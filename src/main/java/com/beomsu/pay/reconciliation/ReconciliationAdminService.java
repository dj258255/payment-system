package com.beomsu.pay.reconciliation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 정산 대사 운영 어드민 — 사람 확인이 필요한 불일치(예외 큐)를 조회한다.
 *
 * <p>대사 엔진이 불일치 3분류(내부만/외부만/금액불일치)를 {@link ReconStatus#PENDING}으로 남기지만
 * 조회할 방법이 없었다. 이 어드민이 예외 큐를 관측 가능하게 한다(관측 전용 — 해소는 별도 절차).
 */
@Service
@RequiredArgsConstructor
public class ReconciliationAdminService {

    private final ReconciliationResultRepository repository;

    /** 사람 확인이 필요한 불일치(PENDING) 목록. */
    @Transactional(readOnly = true)
    public List<ReconMismatchView> listMismatches() {
        return repository.findByStatus(ReconStatus.PENDING).stream()
                .map(r -> new ReconMismatchView(r.getId(), r.getOrderNo(), r.getResult(),
                        r.getInternalAmount(), r.getExternalAmount(), r.getReconciledAt()))
                .toList();
    }
}
