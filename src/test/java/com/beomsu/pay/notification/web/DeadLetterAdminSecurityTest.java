package com.beomsu.pay.notification.web;

import com.beomsu.pay.SecurityConfig;
import com.beomsu.pay.notification.NotificationAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 어드민 엔드포인트 접근 제어 검증 — 인증 없이는 막히고, ROLE_ADMIN이면 통과한다.
 * (보안 검토 지적: 어드민 엔드포인트에 인가가 없던 것을 SecurityConfig로 보호했다.)
 *
 * <p>인증이 JWT Bearer(OAuth2 Resource Server)로 바뀌어, 실제 토큰 대신
 * {@code jwt()} post-processor로 권한을 주입해 인가 규칙만 검증한다. {@code JwtDecoder}는
 * 리소스 서버 필터체인 구성에 필요하므로 목으로 제공한다(실제 디코딩은 post-processor가 우회).
 */
@WebMvcTest(DeadLetterAdminController.class)
@Import(SecurityConfig.class)
class DeadLetterAdminSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    NotificationAdminService adminService;

    @MockitoBean
    JwtDecoder jwtDecoder;

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
    @DisplayName("ROLE_ADMIN 토큰이면 DLQ 조회 통과")
    void listWithAdminIsOk() throws Exception {
        org.mockito.Mockito.when(adminService.listDeadLetters()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/dead-letters")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ROLE_USER 토큰은 어드민 접근 → 403 Forbidden")
    void userRoleForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dead-letters")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }
}
