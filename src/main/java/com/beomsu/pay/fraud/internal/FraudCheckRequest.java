package com.beomsu.pay.fraud.internal;

/**
 * FDS 평가 입력.
 *
 * <p>velocity 를 세는 키는 <b>카드·기기·IP</b> 셋이다. 카드만 세면 카드를 바꿔 가며 같은
 * 기기·같은 회선에서 두드리는 것을 못 본다. {@code userId} 는 아직 규칙에 쓰지 않는다 —
 * 로그인 없이도 결제가 들어오는 경로가 있어 키로서 빈 값이 많다.
 */
public record FraudCheckRequest(long userId, String cardKey, String ip, String deviceId, long amount) {
}
