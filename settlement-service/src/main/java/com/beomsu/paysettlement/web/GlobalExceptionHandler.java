package com.beomsu.paysettlement.web;

import com.beomsu.paysettlement.SettlementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * 도메인 예외 → HTTP 응답 변환. pay-core와 동일한 {@code {code, message, traceId}} 바디 계약을
 * 유지해 데모 콘솔·클라이언트가 서비스 분리를 눈치채지 못하게 한다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    record ErrorResponse(String code, String message, String traceId) {
    }

    @ExceptionHandler(SettlementException.class)
    ResponseEntity<ErrorResponse> handleDomain(SettlementException ex) {
        HttpStatus status = ex.code().endsWith("_NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status)
                .body(new ErrorResponse(ex.code(), ex.getMessage(), UUID.randomUUID().toString()));
    }
}
