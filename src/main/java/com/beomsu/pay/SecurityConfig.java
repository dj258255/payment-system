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
 * <p>백오피스 어드민({@code /api/v1/admin/**})은 <b>ROLE_ADMIN 인증</b>을 요구한다 — DLQ 재처리처럼
 * 상태를 바꾸는 운영 액션을 아무나 호출하지 못하게 한다. 나머지 결제 흐름(주문/승인)은 사용자
 * 인증 체계가 별도로 붙는 영역이라 여기서는 열어 두고, 웹훅은 HMAC 서명으로 자체 인증한다.
 *
 * <p>어드민 자격증명은 프로퍼티로 주입한다({@code app.admin.username/password}). 운영에서는 반드시
 * 강한 비밀번호로 오버라이드해야 한다(기본값은 로컬 개발용).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 세션 없는 API + HMAC 웹훅이라 CSRF 토큰은 부적합 → 비활성화
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")   // 어드민만
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")        // 메트릭도 보호
                        .anyRequest().permitAll())                              // 결제 흐름·웹훅
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    UserDetailsService adminUserDetails(
            @Value("${app.admin.username:admin}") String username,
            @Value("${app.admin.password:admin-local-only}") String password,
            PasswordEncoder encoder) {
        UserDetails admin = User.withUsername(username)
                .password(encoder.encode(password))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
