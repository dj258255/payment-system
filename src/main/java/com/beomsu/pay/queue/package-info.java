/**
 * 선착순 대기열(queue) 모듈 — 순간 트래픽 폭증으로부터 DB를 지키는 유입 제어 프리미티브.
 *
 * <p>한정판 선착순 이벤트처럼 순간에 수만 명이 몰리면, 모두가 동시에 주문·재고 차감을 때려 DB가
 * 무너진다. 그래서 결제 앞단에 <b>대기열</b>을 두어 DB 유입량을 조절한다 — 도착 순서(FIFO)로 줄을
 * 세우고, 앞에서부터 정해진 인원(admit-limit)만 "입장"시켜 결제로 흘려보낸다. 나머지는 자기 순번을
 * 폴링하며 기다린다.
 *
 * <p>줄서기 자료구조는 <b>Redis Sorted Set(ZSET)</b>이다. {@code score=도착순번}(INCR로 단조 증가),
 * {@code member=userId}로 넣으면 순번이 있는 대기열이 된다 — {@code ZRANK}가 O(log N)으로 "내 앞에
 * 몇 명"을 돌려주고, 그 rank가 admit-limit보다 작으면 입장이다. DB를 쓰지 않으므로(Redis 전용)
 * Flyway 마이그레이션도 없다.
 *
 * <p><b>크리티컬 경로(체크아웃)와는 결합하지 않는다.</b> 대기열은 입장/상태/이탈만 제공하는 독립
 * 프리미티브이고, "입장이 확인되면 결제로 진행"은 클라이언트 흐름이 조율한다 — 실제 결제 승인 API를
 * 대기열이 게이팅하지 않는다(결제 경로에 Redis 의존을 심지 않는다). 그래서 어떤 결제/주문 모듈에도
 * 의존하지 않으며, Spring 인프라({@code StringRedisTemplate})만 사용한다(모듈 의존 없음).
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {}
)
package com.beomsu.pay.queue;
