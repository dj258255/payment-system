package com.beomsu.pay;

import com.beomsu.pay.member.Member;
import com.beomsu.pay.member.MemberRepository;
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
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Map;

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
                        // 회원 가입은 로그인 전에 가능해야 하므로 개방한다(이후 이메일로 /auth/login).
                        .requestMatchers(HttpMethod.POST, "/api/v1/members/signup").permitAll()
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

    /**
     * 복합 UserDetailsService — 로그인 식별자로 (a) 먼저 인메모리 데모 계정(admin/admin2/"1"/"2")을
     * 찾고, 없으면 (b) MemberRepository로 이메일 회원을 찾는다. 둘 다 없으면 UsernameNotFoundException.
     *
     * <p><b>숫자 userId 계약 보존</b>: DaoAuthenticationProvider는 인증 성공 시 <i>로드된 UserDetails의
     * getUsername()</i>을 principal 이름으로 삼는다(입력한 로그인 식별자가 아니라). 그래서 회원이
     * 이메일로 로그인해도, 여기서 username을 회원의 숫자 id({@code String.valueOf(member.getId())})로
     * 만들어 반환하면 {@code auth.getName()}=숫자 id가 되고 JWT subject도 숫자로 유지된다. 이 계약이
     * 깨지면 order/payment/wallet/point/subscription의 {@code Long.parseLong(principal.getName())}
     * 소유권 검증이 전부 무너진다.
     *
     * <p>MemberRepository를 생성자(빈 파라미터)로 주입해 지연 없이 조회한다. 인메모리 조회는 로컬이라
     * 빠르고, 회원 조회는 데모 계정이 아닐 때만 1회 DB 히트한다.
     */
    @Bean
    UserDetailsService userDetailsService(
            @Value("${app.admin.username:admin}") String adminUsername,
            @Value("${app.admin.password:admin-local-only}") String adminPassword,
            @Value("${app.user.password:user-local-only}") String userPassword,
            PasswordEncoder encoder,
            MemberRepository memberRepository) {
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
        InMemoryUserDetailsManager inMemory = new InMemoryUserDetailsManager(admin, admin2, user1, user2);

        return username -> {
            // (a) 데모 계정 우선 — admin/admin2/"1"/"2"는 인메모리 그대로 유지(기존 로그인 무중단).
            try {
                return inMemory.loadUserByUsername(username);
            } catch (UsernameNotFoundException notDemo) {
                // (b) 실 회원 — 이메일로 조회. username(=UserDetails.getUsername())을 회원의 숫자 id로
                // 만들어, DaoAuthenticationProvider가 이 값을 principal 이름(=JWT subject)으로 쓰게 한다.
                // 가입 시 이메일을 trim().toLowerCase()로 정규화해 저장하므로, 로그인 조회도 같은 정규화를
                // 적용해야 대소문자·공백이 섞인 입력으로도 로그인된다.
                String normalizedEmail = username == null ? null : username.trim().toLowerCase();
                Member member = memberRepository.findByEmail(normalizedEmail)
                        .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
                return User.withUsername(String.valueOf(member.getId()))
                        .password(member.getPasswordHash())
                        .roles(member.getRole())
                        .build();
            }
        };
    }

    /**
     * 비밀번호 인코더 — <b>BCrypt로 인코딩하되 알고리즘 접두사를 붙인다</b>(ADR-009).
     *
     * <p>BCrypt 자체는 아직 안전하지만 <b>1순위 권고가 아니다.</b> OWASP는 Argon2id를 먼저 권하고
     * BCrypt는 그 다음으로 둔다. 한계는 두 가지 — 비밀번호가 <b>72바이트에서 잘리고</b>, 메모리 하드가
     * 약해 전용 하드웨어 공격에 상대적으로 취약하다.
     *
     * <p>지금 바꾸지 않는 이유는 <b>Argon2/SCrypt 인코더가 BouncyCastle을 요구</b>하는데 이 프로젝트에
     * 그 의존이 없어서다. 의존을 하나 더 들이는 것보다, <b>나중에 바꿀 수 있게 열어 두는 것</b>을 먼저 한다.
     *
     * <p>그래서 {@link DelegatingPasswordEncoder}로 감싼다. 새로 만드는 해시에는 {@code {bcrypt}}
     * 접두사가 붙어, 나중에 맵에 argon2를 추가하고 기본 id만 바꾸면 <b>기존 해시를 그대로 둔 채</b>
     * 새 가입부터 새 알고리즘으로 넘어간다. 접두사가 없으면 어떤 알고리즘으로 만든 해시인지 알 수 없어
     * 전수 비밀번호 재설정 말고는 이관할 방법이 없다.
     *
     * <p>{@code setDefaultPasswordEncoderForMatches}는 <b>접두사 없이 이미 저장된 기존 해시</b>를
     * 계속 검증하기 위한 것이다. 이게 없으면 기존 회원이 전부 로그인하지 못한다.
     *
     * <p><b>주의</b>: 인코더를 바꿔도 {@code /auth/login}에서 BCrypt가 1회 도는 사실은 그대로다.
     * 비밀번호를 실제로 대조하는 자리라 없앨 수 없고, 그래서 그 경로는 IP 기준 유입 제한으로 막는다
     * ({@link RateLimitFilter}).
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        String encodingId = "bcrypt";
        Map<String, PasswordEncoder> encoders = Map.of(encodingId, new BCryptPasswordEncoder());

        DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder(encodingId, encoders);
        // 접두사 없는 레거시 해시(이미 DB에 있는 것)를 계속 검증한다.
        delegating.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return delegating;
    }
}
