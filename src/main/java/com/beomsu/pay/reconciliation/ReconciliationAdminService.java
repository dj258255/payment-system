package com.beomsu.pay.reconciliation;

import com.beomsu.pay.reconciliation.PgSettlementCsvParser.ParseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;

/**
 * 정산 대사 운영 어드민 — PG 정산 파일로 대사를 실행하고, 사람 확인이 필요한 불일치(예외 큐)를 조회·확정한다.
 *
 * <p>대사 엔진이 불일치 3분류(내부만/외부만/금액불일치)를 {@link ReconStatus#PENDING}으로 남기지만
 * (1) 외부 기록을 실제로 넣을 경로가 없었고 (2) 남은 예외 큐를 조회할 방법도 없었다. 이 어드민이
 * <b>업로드 → 대사 → 예외 큐 → 수기 확정</b> 루프를 닫는다.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationAdminService {

    private final ReconciliationResultRepository repository;
    private final PgSettlementCsvParser parser;
    private final ReconciliationService reconciliationService;

    /**
     * PG 정산 파일(CSV)을 파싱해 대사 매칭 엔진을 돌리고 결과를 분류별로 집계한다.
     *
     * <p>파싱은 헤더 기반이라 컬럼 순서·부가 컬럼에 강하고, 불량/요약 행은 건너뛴다(스킵 수 집계).
     * 매칭 엔진({@link ReconciliationService#reconcile})이 결과를 {@code saveAll}로 이미 영속하므로,
     * 여기서는 반환된 결과를 타입별로 세어 요약만 만든다. 불일치는 PENDING 예외 큐로 남아 수기 확정을 기다린다.
     */
    @Transactional
    public ReconRunSummary run(InputStream file) {
        ParseResult parsed = parser.parse(file);
        List<ReconciliationResult> results = reconciliationService.reconcile(parsed.records());

        int matched = 0, internalOnly = 0, externalOnly = 0, amountMismatch = 0;
        for (ReconciliationResult r : results) {
            switch (r.getResult()) {
                case MATCHED -> matched++;
                case INTERNAL_ONLY -> internalOnly++;
                case EXTERNAL_ONLY -> externalOnly++;
                case AMOUNT_MISMATCH -> amountMismatch++;
            }
        }
        int pending = internalOnly + externalOnly + amountMismatch;
        return new ReconRunSummary(parsed.records().size(), parsed.skipped(),
                matched, internalOnly, externalOnly, amountMismatch, pending);
    }

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
