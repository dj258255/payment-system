package com.beomsu.pay.reconciliation.web;

import com.beomsu.pay.reconciliation.ReconMismatchView;
import com.beomsu.pay.reconciliation.ReconciliationAdminService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * 정산 대사 운영 어드민 REST 컨트롤러(PENDING 조회 + 수기 확정).
 *
 * <p>인가는 {@code SecurityConfig}에서 {@code /api/v1/admin/**}에 ROLE_ADMIN을 요구해 강제한다.
 * 상태를 바꾸는 수기 확정은 호출자(principal)를 감사 로그로 남긴다(기존 DLQ 어드민과 같은 결).
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliations")
@RequiredArgsConstructor
class ReconciliationAdminController {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final ReconciliationAdminService adminService;

    @GetMapping("/mismatches")
    List<ReconMismatchView> mismatches() {
        return adminService.listMismatches();
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
