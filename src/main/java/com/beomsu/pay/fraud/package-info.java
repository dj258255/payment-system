/**
 * 이상거래탐지(fraud/FDS) 모듈.
 *
 * <p>결제 요청을 룰 기반으로 평가해 위험 점수를 매기고 ALLOW/CHALLENGE/BLOCK/REVIEW로 대응한다.
 * 룰은 코드가 아니라 데이터(임계값·가중치)로 관리해 무배포 조정한다. 핵심은 탐지 정확도가 아니라
 * 아키텍처 판단(동기 인라인 판정 vs 비동기 사후 탐지, 무배포 룰 변경)이다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared" }
)
package com.beomsu.pay.fraud;
