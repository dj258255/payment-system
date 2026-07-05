package com.beomsu.pay.fraud;

/** FDS 평가 입력. velocity 키(사용자/카드/기기/IP)와 금액. */
public record FraudCheckRequest(long userId, String cardKey, String ip, String deviceId, long amount) {
}
