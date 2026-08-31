package com.beomsu.pay.payment.web;

import com.beomsu.pay.MetricsTestConfig;
import com.beomsu.pay.RateLimiter;
import com.beomsu.pay.SecurityConfig;
import com.beomsu.pay.member.MemberRepository;
import com.beomsu.pay.payment.pg.PgSelector;
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
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결제창을 띄우기 전에 PG를 고르는 창구.
 *
 * <p>라우팅을 끄면 빈이 없으므로 {@code routed=false}로 나가고, 프론트는 기본 PG를 쓴다.
 * 이 경로가 실패하면 결제 자체를 시작할 수 없으므로 <b>절대 500을 내지 않는다.</b>
 */
@WebMvcTest(PgSelectionController.class)
@Import({SecurityConfig.class, MetricsTestConfig.class})
class PgSelectionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PgSelector pgSelector;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    RateLimiter rateLimiter;

    @MockitoBean
    MemberRepository memberRepository;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    @DisplayName("서버가 고른 PG를 내려준다")
    void returnsSelectedProvider() throws Exception {
        when(pgSelector.select()).thenReturn(Optional.of("toss"));

        mockMvc.perform(post("/api/v1/payments/init").with(user()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("toss"))
                .andExpect(jsonPath("$.routed").value(true));
    }

    @Test
    @DisplayName("고를 게 없으면 routed=false — 프론트가 기본 PG를 쓴다")
    void fallsBackWhenNoRoute() throws Exception {
        when(pgSelector.select()).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/payments/init").with(user()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routed").value(false));
    }

    @Test
    @DisplayName("인증 없이 부르면 401")
    void requiresAuth() throws Exception {
        mockMvc.perform(post("/api/v1/payments/init").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
        return org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.csrf();
    }
}
