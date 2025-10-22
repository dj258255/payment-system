package com.beomsu.pay.notification.web;

import com.beomsu.pay.SecurityConfig;
import com.beomsu.pay.notification.NotificationAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 어드민 엔드포인트 접근 제어 검증 — 인증 없이는 막히고, ROLE_ADMIN이면 통과한다.
 * (보안 검토 지적: 어드민 엔드포인트에 인가가 없던 것을 SecurityConfig로 보호했다.)
 */
@WebMvcTest(DeadLetterAdminController.class)
@Import(SecurityConfig.class)
class DeadLetterAdminSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    NotificationAdminService adminService;

    @Test
    @DisplayName("인증 없이 DLQ 조회 → 401 Unauthorized")
    void listWithoutAuthIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dead-letters"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("인증 없이 재처리(상태 변경) → 401 Unauthorized")
    void reprocessWithoutAuthIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/admin/dead-letters/1/reprocess"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ROLE_ADMIN 인증이면 DLQ 조회 통과")
    void listWithAdminIsOk() throws Exception {
        org.mockito.Mockito.when(adminService.listDeadLetters()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/dead-letters")
                        .with(httpBasic("admin", "admin-local-only")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("잘못된 자격증명 → 401")
    void wrongCredentialsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dead-letters")
                        .with(httpBasic("admin", "wrong")))
                .andExpect(status().isUnauthorized());
    }
}
