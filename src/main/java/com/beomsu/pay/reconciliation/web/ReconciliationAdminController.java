package com.beomsu.pay.reconciliation.web;

import com.beomsu.pay.reconciliation.ReconMismatchView;
import com.beomsu.pay.reconciliation.ReconRunSummary;
import com.beomsu.pay.reconciliation.ReconciliationAdminService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.Principal;

/**
 * 정산 대사 운영 어드민 REST 컨트롤러(정산 파일 업로드 대사 + PENDING 조회 + 수기 확정).
 *
 * <p>인가는 {@code SecurityConfig}에서 {@code /api/v1/admin/**}에 ROLE_ADMIN을 요구해 강제한다.
 * 상태를 바꾸는 실행/수기 확정은 호출자(principal)를 감사 로그로 남긴다(기존 DLQ 어드민과 같은 결).
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliations")
@RequiredArgsConstructor
class ReconciliationAdminController {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final ReconciliationAdminService adminService;

    /**
     * PG 정산 파일(CSV)을 업로드해 대사를 실행한다. 결과는 분류별 집계({@link ReconRunSummary})로 응답하고,
     * 불일치는 PENDING 예외 큐로 남아 {@code /mismatches} 조회 → {@code /{id}/resolve} 수기 확정으로 이어진다.
     */
    @PostMapping("/run")
    ReconRunSummary run(@RequestParam("file") MultipartFile file, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("정산 파일 대사 실행 요청 by={} filename={} size={}",
                who, file.getOriginalFilename(), file.getSize());
        ReconRunSummary summary;
        try {
            summary = adminService.run(file.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        audit.info("정산 파일 대사 실행 결과 by={} external={} skipped={} matched={} pending={}",
                who, summary.external(), summary.skipped(), summary.matched(), summary.pending());
        return summary;
    }

    @GetMapping("/mismatches")
    Page<ReconMismatchView> mismatches(@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return adminService.listMismatches(pageable);
    }

    @PostMapping("/{id}/resolve")
    ReconMismatchView resolve(@PathVariable Long id, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("대사 불일치 수기 확정 요청 by={} reconResultId={}", who, id);
        ReconMismatchView view = adminService.resolve(id);
        audit.info("대사 불일치 수기 확정 결과 by={} reconResultId={}", who, id);
        return view;
    }
}
