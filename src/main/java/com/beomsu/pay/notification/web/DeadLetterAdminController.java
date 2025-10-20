package com.beomsu.pay.notification.web;

import com.beomsu.pay.notification.DeadLetterView;
import com.beomsu.pay.notification.NotificationAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * DLQ 백오피스 어드민 REST 컨트롤러.
 * (운영에서는 인증·RBAC·감사 로그가 붙는다 — 08 문서의 maker-checker 참조.)
 */
@RestController
@RequestMapping("/api/v1/admin/dead-letters")
@RequiredArgsConstructor
class DeadLetterAdminController {

    private final NotificationAdminService adminService;

    @GetMapping
    List<DeadLetterView> list() {
        return adminService.listDeadLetters();
    }

    @PostMapping("/{id}/reprocess")
    Map<String, Object> reprocess(@PathVariable Long id) {
        boolean ok = adminService.reprocess(id);
        return Map.of("id", id, "reprocessed", ok);
    }
}
