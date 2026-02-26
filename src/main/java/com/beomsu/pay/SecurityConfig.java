package com.beomsu.pay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 보안 설정.
 *
 * <p>접근 제어:
 * <ul>
 *   <li>{@code POST /api/v1/auth/login} → 개방(로그인, 여기서만 BCrypt 1회 검증 후 JWT 발급)</li>
 *   <li>{@code /api/v1/admin/**} → ROLE_ADMIN (DLQ 재처리 등 운영 액션). 강제취소 maker-checker
 *       시연을 위해 2번째 어드민({@code admin2})을 둔다 — 요청자≠승인자를 강제하려면 서로 다른 어드민이 필요.</li>
 *   <li>{@code /api/v1/orders/**}, {@code /api/v1/payments/confirm} → ROLE_USER 인증.
 *       userId는 인증된 principal에서 얻어 <b>주문 소유권을 검증</b>한다(IDOR 방지).</li>
 *   <li>{@code /api/v1/webhooks/**} → 개방(HMAC 서명으로 자체 인증)</li>
 *   <li>{@code /actuator} → ADMIN. 단 {@code health/info}와 {@code prometheus}(수집기 스크레이프)는 공개</li>
 * </ul>
 *
 * <p>인증은 <b>JWT Bearer(OAuth2 Resource Server, Nimbus HS256 대칭키)</b>로 한다. 무상태 HTTP
 * Basic이 요청마다 BCrypt로 비밀번호를 재검증하던 병목(min ~110ms)을 없애기 위해, BCrypt 검증은
 * 로그인({@link AuthController}) 1회로 밀어내고 이후 요청은 대칭키 서명만 빠르게 검증한다.
 * 토큰 subject에 userId(데모 유저 username "1" 등)를 실어 {@code principal.getName()}이 계속
 * userId를 반환한다 — "userId를 클라이언트가 아니라 인증 컨텍스트에서 얻는다"는 원칙은 그대로다.
 * {@code UserDetailsService}(데모 유저)와 BCrypt 인코더는 로그인 검증에 계속 쓰인다.
 * 기본 자격증명/시크릿은 로컬 개발용 — 운영에서 반드시 오버라이드.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter,
                                    RateLimiter rateLimiter,
                                    @Value("${app.ratelimit.enabled:true}") boolean rateLimitEnabled,
                                    @Value("${app.ratelimit.per-user-per-sec:5}") int perUserPerSec,
                                    @Value("${app.ratelimit.global-per-sec:100}") int globalPerSec)
            throws Exception {
        http
                // 세션 없는 API + HMAC 웹훅이라 CSRF 토큰은 부적합 → 비활성화
                .csrf(csrf -> csrf.disable())
                // JWT 무상태 인증 — 서버 세션을 만들지 않는다
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 로그인(BCrypt 1회)·갱신(refresh 자체가 소유 증명)은 개방,
                        // 로그아웃은 현재 access를 폐기하므로 인증 필요.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/webhooks/**").permitAll()      // HMAC 자체 인증
                        .requestMatchers("/api/v1/orders/**", "/api/v1/payments/**").hasRole("USER")
                        .requestMatchers("/api/v1/subscriptions/**").hasRole("USER")   // 구독은 회원 본인 소유
                        .requestMatchers("/api/v1/wallet/**").hasRole("USER")          // 월렛은 회원 본인 소유
                        .requestMatchers("/api/v1/points/**").hasRole("USER")          // 포인트는 회원 본인 소유
                        // 선착순 대기열: 로그인 사용자만 줄 서기(멤버=인증 principal userId). 결제 경로와는
                        // 결합하지 않는 독립 프리미티브(입장/상태/이탈)이지만 참가자 식별을 위해 인증은 요구한다.
                        .requestMatchers("/api/v1/queue/**").hasRole("USER")
                        // health/info와 Prometheus 스크레이프 엔드포인트는 개방한다. prometheus는
                        // 메트릭 수집기가 Bearer 없이 주기 GET 해야 하므로 인증을 걸면 스크레이프가 401로
                        // 막힌다. 운영에선 management.server.port를 내부망 전용으로 분리해 스크레이프하는
                        // 게 정석이나, 여기선 로컬 Prometheus를 위해 이 엔드포인트만 개방한다.
                        // 나머지 actuator(env·heapdump·modulith 등 정찰 소지)는 계속 ADMIN으로 잠근다.
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                // Bearer 토큰의 HS256 서명만 검증(요청당 BCrypt 없음)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                // 유입 제어(rate limit): Bearer 인증 "뒤"에 끼워 principal(userId)로 per-user 키를
                // 만든다. 필터를 빈으로 등록하지 않고 여기서 직접 생성한다 — 빈이면 서블릿 컨테이너가
                // 자동으로 한 번 더 등록해 같은 요청에 이중 적용되기 때문(RateLimitFilter 주석 참고).
                .addFilterAfter(new RateLimitFilter(rateLimiter, rateLimitEnabled, perUserPerSec, globalPerSec),
                        BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * JWT "roles" 클레임 → 권한 매핑. issue 시 "ROLE_USER"/"ROLE_ADMIN"로 저장하므로 prefix 없이
     * 그대로 authority가 되어 {@code hasRole("USER")}/{@code hasRole("ADMIN")}와 맞물린다.
     * principal name은 기본값(sub 클레임) = userId.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");   // roles가 이미 "ROLE_" 접두사를 포함
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * 로그인 검증용 AuthenticationManager — 아래 UserDetailsService + BCrypt 인코더로 구성된다.
     * {@link AuthController}가 로그인 시 이 매니저로 BCrypt 검증을 1회 수행한다.
     */
    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    UserDetailsService userDetailsService(
            @Value("${app.admin.username:admin}") String adminUsername,
            @Value("${app.admin.password:admin-local-only}") String adminPassword,
            @Value("${app.user.password:user-local-only}") String userPassword,
            PasswordEncoder encoder) {
        UserDetails admin = User.withUsername(adminUsername)
                .password(encoder.encode(adminPassword)).roles("ADMIN").build();
        // maker-checker용 2번째 어드민: 강제취소는 요청자≠승인자를 강제하므로, admin이 요청하고
        // admin2가 승인하는 2인 흐름을 실제로 시연하려면 서로 다른 어드민이 필요하다.
        UserDetails admin2 = User.withUsername("admin2")
                .password(encoder.encode(adminPassword)).roles("ADMIN").build();
        // 데모 사용자: username이 곧 userId (principal.getName() → Long.parseLong)
        UserDetails user1 = User.withUsername("1")
                .password(encoder.encode(userPassword)).roles("USER").build();
        UserDetails user2 = User.withUsername("2")
                .password(encoder.encode(userPassword)).roles("USER").build();
        return new InMemoryUserDetailsManager(admin, admin2, user1, user2);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
