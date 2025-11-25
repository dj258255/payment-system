package com.beomsu.pay;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 로그인 → JWT 발급 엔드포인트.
 *
 * <p>여기서만 BCrypt로 자격증명을 1회 검증하고(무상태 HTTP Basic의 요청당 재해싱 병목 제거),
 * 성공 시 subject=userId인 JWT를 발급한다. 이후 보호 엔드포인트는 Bearer 토큰의 서명만
 * 검증하므로 빠르다. subject를 username(=userId)으로 유지해 {@code principal.getName()} 기반
 * 소유권 검증이 그대로 동작한다.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        // BCrypt 검증은 이 1회뿐 — 실패 시 BadCredentialsException → 아래 핸들러가 401.
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)   // "ROLE_USER" / "ROLE_ADMIN"
                .toList();

        String token = jwtService.issue(auth.getName(), roles);
        return new LoginResponse(token, "Bearer", jwtService.expirySeconds());
    }

    /**
     * 컨트롤러 내부에서 던져진 인증 예외는 시큐리티 필터가 감싸지 못하므로 여기서 401로 변환한다
     * (변환하지 않으면 500이 된다).
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleAuthenticationFailure(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("code", "AUTHENTICATION_FAILED", "message", "자격 증명이 올바르지 않습니다."));
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, String tokenType, long expiresInSeconds) {
    }
}
