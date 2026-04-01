package com.beomsu.paysettlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 정산 서비스 — pay 모놀리스에서 추출된 첫 서비스.
 *
 * <p>결제·에스크로 이벤트를 Kafka로 구독해(컨슈머 그룹 {@code settlement-service}) 정산 항목을
 * 적재·확정·집계하고, 어드민 API(조회·지급확정·수동실행)와 일 배치 스케줄러를 소유한다.
 * 정산 스케줄러의 <b>단일 실행 주체</b>가 이 서비스다 — pay-core는 이제 자유롭게 수평 확장한다.
 *
 * <p>DB는 pay와 분리된 {@code pay_settlement} 스키마를 쓴다. 결제 테이블을 조인하지 않고
 * 이벤트로 받은 데이터만으로 집계한다(추출 전부터 지켜진 경계라 코드 변경 없이 분리됐다).
 */
@SpringBootApplication
public class PaySettlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaySettlementApplication.class, args);
    }
}
