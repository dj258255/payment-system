package com.beomsu.pay.assist.web;

import com.beomsu.pay.MetricsTestConfig;
import com.beomsu.pay.RateLimiter;
import com.beomsu.pay.SecurityConfig;
import com.beomsu.pay.assist.ResidualCauseService;
import com.beomsu.pay.assist.ResidualSuggestion;
import com.beomsu.pay.member.MemberRepository;
import com.beomsu.pay.reconciliation.CauseSuggestion;
import com.beomsu.pay.reconciliation.ResolveCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 화면이 실제로 후보를 받는 경로를 고정한다.
 *
 * <p>이 엔드포인트가 assist 쪽에 있는 이유가 모듈 경계다. 대사 어드민이 assist 를
 * 부르면 순환이라, 창구를 여기 두고 화면이 두 번 부른다. 그 구조가 유지되는지는
 * {@code ModularityTests}가 본다.
 */
@WebMvcTest(ResidualCauseAdminController.class)
@Import({SecurityConfig.class, MetricsTestConfig.class})
class ResidualCauseAdminControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ResidualCauseService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    RateLimiter rateLimiter;

    @MockitoBean
    MemberRepository memberRepository;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("인증 없이 부르면 401")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders/ORD-1/residual-cause?reconResultId=1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("후보가 있으면 원인과 근거를 함께 준다")
    void returnsSuggestion() throws Exception {
        when(service.suggest(anyString(), anyLong(), any())).thenReturn(Optional.of(
                new ResidualSuggestion(ResolveCause.PG_FILE_DELAY,
                        "다음 거래일 파일에 같은 주문번호가 있습니다.", 85, Set.of())));

        mockMvc.perform(get("/api/v1/admin/orders/ORD-1/residual-cause?reconResultId=1").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggested").value(true))
                .andExpect(jsonPath("$.cause").value("PG_FILE_DELAY"))
                .andExpect(jsonPath("$.confidence").value(85))
                // 어디서 온 값인지 항상 실어 준다. 규칙 제안과 화면에서 같아 보이면
                // 사람이 둘을 같은 무게로 읽는다.
                .andExpect(jsonPath("$.source").value("model"));
    }

    @Test
    @DisplayName("기권했으면 404가 아니라 200에 suggested=false")
    void abstentionIsNotAnError() throws Exception {
        when(service.suggest(anyString(), anyLong(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/orders/ORD-1/residual-cause?reconResultId=1").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggested").value(false))
                .andExpect(jsonPath("$.cause").doesNotExist());
    }

    @Test
    @DisplayName("규칙이 이미 후보를 냈다고 알려주면 가드 1이 서게 넘긴다")
    void passesRulesDecidedFlag() throws Exception {
        when(service.suggest(anyString(), anyLong(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/orders/ORD-1/residual-cause"
                        + "?reconResultId=1&rulesDecided=true").with(admin()))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CauseSuggestion>> rules = ArgumentCaptor.forClass(List.class);
        verify(service).suggest(anyString(), anyLong(), rules.capture());
        assertThat(rules.getValue()).isNotEmpty();     // 비어 있지 않으면 가드 1이 선다
    }

    @Test
    @DisplayName("기본값은 규칙이 못 냈다고 본다")
    void defaultsToRulesUndecided() throws Exception {
        when(service.suggest(anyString(), anyLong(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/orders/ORD-1/residual-cause?reconResultId=1").with(admin()))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CauseSuggestion>> rules = ArgumentCaptor.forClass(List.class);
        verify(service).suggest(anyString(), anyLong(), rules.capture());
        assertThat(rules.getValue()).isEmpty();
    }
}
