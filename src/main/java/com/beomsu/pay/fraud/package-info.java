/**
 * 이상거래탐지(fraud/FDS) 모듈.
 *
 * <p>결제 요청을 룰 기반으로 평가해 위험 점수를 매기고 ALLOW/CHALLENGE/BLOCK/REVIEW로 대응한다.
 * 룰은 코드가 아니라 데이터(임계값·가중치)로 관리해 무배포 조정한다. 핵심은 탐지 정확도가 아니라
 * 아키텍처 판단(동기 인라인 판정 vs 비동기 사후 탐지, 무배포 룰 변경)이다.
 *
 * <p>동일한 판정 엔진({@link com.beomsu.pay.fraud.FraudService#evaluate})을 두 결에서 재사용한다:
 * (1) 동기 인라인 판정, (2) 결제 완료 이벤트를 받아 사후에 다시 평가하는 <b>비동기 사후 탐지</b>.
 * 사후 탐지는 payment 모듈의 {@code PaymentConfirmedEvent}를 구독하고 {@code paymentKey}를 되읽으므로
 * payment에 의존한다(payment는 fraud를 모르므로 순환 없음). REVIEW/BLOCK 판정은 심사 큐
 * ({@link com.beomsu.pay.fraud.FraudReview})에 적재돼 어드민이 승인/거부한다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "payment" }
)
package com.beomsu.pay.fraud;
