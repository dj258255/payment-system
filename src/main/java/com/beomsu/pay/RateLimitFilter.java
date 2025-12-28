package com.beomsu.pay;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * 쓰기 API 유입 제어 필터 — "감당 못 할 요청은 바깥 층에서 싸게 거절한다(429)".
 *
 * <p>대상은 서버가 실제로 일(DB 쓰기·PG 호출)을 시작하는 두 진입점만이다:
 * {@code POST /api/v1/orders}, {@code POST /api/v1/payments/confirm}(정확 매칭).
 * 한도는 두 겹이다:
 * <ul>
 *   <li><b>per-user</b> — {@code user:{userId}:{path}}: 한 사용자가 초당 수백 번 쳐도
 *       {@code app.ratelimit.per-user-per-sec}(기본 5)까지만 받는다(스크립트/이중클릭 억제).</li>
 *   <li><b>global</b> — {@code global:{path}}: 전체 유입을 {@code app.ratelimit.global-per-sec}
 *       (기본 100)으로 캡 — 사용자가 수만 명이어도 DB로 가는 총량은 상한이 있다.</li>
 * </ul>
 *
 * <p>초과 시 429 + {@code Retry-After: 1}을 <b>필터에서 직접</b> 써서 컨트롤러·서비스·DB 커넥션에
 * 아무 비용도 지우지 않는다. 미인증 요청은 통과시킨다(뒤의 시큐리티가 401로 거절 — principal 없이
 * per-user 키를 만들 수 없고, 익명을 하나의 버킷에 몰면 정상 익명 트래픽까지 오폭한다).
 *
 * <p>이 필터는 <b>@Component가 아니다</b> — SecurityConfig가 시큐리티 체인 안에서
 * {@code BearerTokenAuthenticationFilter} 뒤(인증 확정 후, principal 사용 가능)에 직접 끼운다.
 * 빈으로 두면 서블릿 컨테이너가 자동으로 한 번 더 등록해 이중 적용되므로, 빈 등록 자체를 피했다.
 *
 * <p>{@code app.ratelimit.enabled=false}로 통째로 끌 수 있다 — k6 스파이크를 전/후로 돌려
 * "429로 쳐낸 뒤 성공 요청의 p95가 유지되는가"를 비교하기 위한 스위치.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** 정확 매칭 대상 경로 — 서버 최초 쓰기 진입점만. 조회는 제한하지 않는다. */
    private static final Set<String> TARGET_PATHS =
            Set.of("/api/v1/orders", "/api/v1/payments/confirm");

    private static final Duration WINDOW = Duration.ofSeconds(1);
    private static final String RATE_LIMITED_BODY =
            "{\"code\":\"RATE_LIMITED\",\"message\":\"요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.\"}";

    private final RateLimiter rateLimiter;
    private final boolean enabled;
    private final int perUserPerSec;
    private final int globalPerSec;

    public RateLimitFilter(RateLimiter rateLimiter, boolean enabled,
                           int perUserPerSec, int globalPerSec) {
        this.rateLimiter = rateLimiter;
        this.enabled = enabled;
        this.perUserPerSec = perUserPerSec;
        this.globalPerSec = globalPerSec;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled || !"POST".equals(request.getMethod())
                || !TARGET_PATHS.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            // 미인증은 여기서 판단하지 않는다 — 뒤의 인가 단계가 401로 거절한다.
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        // per-user 먼저(더 좁은 한도) — 초과면 global 카운터는 소비하지 않는다.
        if (!rateLimiter.tryAcquire("user:" + auth.getName() + ":" + path, perUserPerSec, WINDOW)
                || !rateLimiter.tryAcquire("global:" + path, globalPerSec, WINDOW)) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 429를 필터에서 직접 write — 컨트롤러/서비스/DB에 비용 0. */
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", "1");                    // 고정 윈도우 = 1초 뒤 새 윈도우
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(RATE_LIMITED_BODY.getBytes(StandardCharsets.UTF_8));
    }
}
