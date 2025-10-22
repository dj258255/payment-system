package com.beomsu.pay.notification.web;

import com.beomsu.pay.notification.DeadLetterView;
import com.beomsu.pay.notification.NotificationAdminService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * DLQ 백오피스 어드민 REST 컨트롤러.
 *
 * <p>인가는 {@code SecurityConfig}에서 {@code /api/v1/admin/**} 에 ROLE_ADMIN을 요구해 강제한다.
 * 상태를 바꾸는 재처리는 호출자(principal)를 감사 로그로 남긴다(운영에선 maker-checker·감사 테이블로 강화).
 */
@RestController
@RequestMapping("/api/v1/admin/dead-letters")
@RequiredArgsConstructor
class DeadLetterAdminController {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final NotificationAdminService adminService;

    @GetMapping
    List<DeadLetterView> list() {
        return adminService.listDeadLetters();
    }

    @PostMapping("/{id}/reprocess")
    Map<String, Object> reprocess(@PathVariable Long id, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("DLQ 재처리 요청 by={} deadLetterId={}", who, id);
        boolean ok = adminService.reprocess(id);
        audit.info("DLQ 재처리 결과 by={} deadLetterId={} reprocessed={}", who, id, ok);
        return Map.of("id", id, "reprocessed", ok);
    }
}
