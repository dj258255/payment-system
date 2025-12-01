package com.beomsu.pay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 엔드포인트 슬라이스 테스트 — 자격증명 검증 결과에 따른 200/401을 확인한다.
 * AuthenticationManager/JwtService는 목으로 대체(BCrypt 실제 검증은 SecurityConfig 통합 밖).
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AuthenticationManager authenticationManager;

    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    @DisplayName("올바른 자격증명 → 200 + token/tokenType 필드")
    void loginSucceeds() throws Exception {
        var authenticated = new UsernamePasswordAuthenticationToken(
                "1", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(authenticationManager.authenticate(any())).thenReturn(authenticated);
        when(jwtService.issue(eq("1"), any())).thenReturn("issued-token");
        when(jwtService.expirySeconds()).thenReturn(3600L);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"1\",\"password\":\"user-local-only\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("issued-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(3600));
    }

    @Test
    @DisplayName("잘못된 자격증명 → 401 Unauthorized")
    void loginRejectsBadCredentials() throws Exception {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"1\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }
}
