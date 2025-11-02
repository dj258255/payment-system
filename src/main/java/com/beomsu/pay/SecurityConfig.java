package com.beomsu.pay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 보안 설정.
 *
 * <p>접근 제어:
 * <ul>
 *   <li>{@code /api/v1/admin/**} → ROLE_ADMIN (DLQ 재처리 등 운영 액션)</li>
 *   <li>{@code /api/v1/orders/**}, {@code /api/v1/payments/confirm} → ROLE_USER 인증.
 *       userId는 인증된 principal에서 얻어 <b>주문 소유권을 검증</b>한다(IDOR 방지).</li>
 *   <li>{@code /api/v1/webhooks/**} → 개방(HMAC 서명으로 자체 인증)</li>
 *   <li>{@code /actuator} 메트릭 → ADMIN (health/info만 공개)</li>
 * </ul>
 *
 * <p>자격증명은 프로퍼티로 주입한다. 데모 사용자 username은 곧 userId다(예: "1"). 운영에서는
 * 실제 사용자 인증 체계(JWT/세션/게이트웨이)로 교체하되, "userId를 클라이언트가 아니라 인증
 * 컨텍스트에서 얻는다"는 원칙은 동일하다. 기본 비밀번호는 로컬 개발용 — 운영에서 반드시 오버라이드.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 세션 없는 API + HMAC 웹훅이라 CSRF 토큰은 부적합 → 비활성화
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/webhooks/**").permitAll()      // HMAC 자체 인증
                        .requestMatchers("/api/v1/orders/**", "/api/v1/payments/**").hasRole("USER")
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    UserDetailsService userDetailsService(
            @Value("${app.admin.username:admin}") String adminUsername,
            @Value("${app.admin.password:admin-local-only}") String adminPassword,
            @Value("${app.user.password:user-local-only}") String userPassword,
            PasswordEncoder encoder) {
        UserDetails admin = User.withUsername(adminUsername)
                .password(encoder.encode(adminPassword)).roles("ADMIN").build();
        // 데모 사용자: username이 곧 userId (principal.getName() → Long.parseLong)
        UserDetails user1 = User.withUsername("1")
                .password(encoder.encode(userPassword)).roles("USER").build();
        UserDetails user2 = User.withUsername("2")
                .password(encoder.encode(userPassword)).roles("USER").build();
        return new InMemoryUserDetailsManager(admin, user1, user2);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
