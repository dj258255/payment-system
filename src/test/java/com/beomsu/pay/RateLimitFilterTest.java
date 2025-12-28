package com.beomsu.pay;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimitFilter 단위 테스트 — MockHttpServletRequest/Response + RateLimiter 목.
 * 대상 경로 429(직접 write + Retry-After), 비대상/미인증/disabled 통과를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    private static final int PER_USER = 5;
    private static final int GLOBAL = 100;

    @Mock
    RateLimiter rateLimiter;

    MockHttpServletResponse response;
    MockFilterChain chain;

    @BeforeEach
    void setUp() {
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        // BearerTokenAuthenticationFilter 뒤에 놓인 상황을 재현 — 인증이 이미 확정돼 있다.
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("1", null, "ROLE_USER"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private RateLimitFilter filter(boolean enabled) {
        return new RateLimitFilter(rateLimiter, enabled, PER_USER, GLOBAL);
    }

    private static MockHttpServletRequest post(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    @DisplayName("대상 경로 + per-user 한도 초과: 429 + Retry-After:1 + RATE_LIMITED JSON, 체인 진행 안 함")
    void overLimitRejectedWith429() throws ServletException, IOException {
        when(rateLimiter.tryAcquire(eq("user:1:/api/v1/orders"), eq(PER_USER), any(Duration.class)))
                .thenReturn(false);

        filter(true).doFilter(post("/api/v1/orders"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).contains("RATE_LIMITED");
        assertThat(chain.getRequest()).isNull();                    // 컨트롤러까지 안 감(싸게 거절)
        // per-user에서 이미 초과 → global 카운터는 소비하지 않는다.
        verify(rateLimiter, never()).tryAcquire(eq("global:/api/v1/orders"), anyInt(), any(Duration.class));
    }

    @Test
    @DisplayName("global 한도 초과(per-user는 통과): 429")
    void globalOverLimitRejected() throws ServletException, IOException {
        when(rateLimiter.tryAcquire(eq("user:1:/api/v1/payments/confirm"), eq(PER_USER), any(Duration.class)))
                .thenReturn(true);
        when(rateLimiter.tryAcquire(eq("global:/api/v1/payments/confirm"), eq(GLOBAL), any(Duration.class)))
                .thenReturn(false);

        filter(true).doFilter(post("/api/v1/payments/confirm"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("대상 경로 + 한도 이내: per-user·global 둘 다 확인 후 통과")
    void withinLimitPasses() throws ServletException, IOException {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);

        filter(true).doFilter(post("/api/v1/orders"), response, chain);

        assertThat(chain.getRequest()).isNotNull();                 // 체인 계속
        assertThat(response.getStatus()).isEqualTo(200);
        verify(rateLimiter).tryAcquire(eq("user:1:/api/v1/orders"), eq(PER_USER), any(Duration.class));
        verify(rateLimiter).tryAcquire(eq("global:/api/v1/orders"), eq(GLOBAL), any(Duration.class));
    }

    @Test
    @DisplayName("비대상 경로(조회 등)는 rate limiter를 아예 타지 않고 통과")
    void nonTargetPathBypasses() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/ord-1");
        request.setRequestURI("/api/v1/orders/ord-1");

        filter(true).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    @DisplayName("정확 매칭: /api/v1/orders/xxx 같은 하위 경로 POST는 대상이 아니다")
    void subPathIsNotTarget() throws ServletException, IOException {
        filter(true).doFilter(post("/api/v1/orders/ord-1/cancel"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    @DisplayName("disabled(app.ratelimit.enabled=false)면 대상 경로도 통과 — k6 전/후 비교 스위치")
    void disabledPassesThrough() throws ServletException, IOException {
        filter(false).doFilter(post("/api/v1/orders"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    @DisplayName("미인증 요청은 통과(뒤의 시큐리티가 401 처리) — 익명 버킷 오폭 방지")
    void unauthenticatedPassesThrough() throws ServletException, IOException {
        SecurityContextHolder.clearContext();

        filter(true).doFilter(post("/api/v1/orders"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any(Duration.class));
    }
}
