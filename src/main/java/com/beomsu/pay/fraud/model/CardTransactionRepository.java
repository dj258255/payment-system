package com.beomsu.pay.fraud.model;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 ModularityTests 가 막는다.
public interface CardTransactionRepository extends JpaRepository<CardTransaction, Long> {

    /**
     * 그 카드의 최근 결제. <b>상한을 걸어 받는다.</b>
     *
     * <p>상황 2.3 에서 배치 조회 15곳에 상한을 걸었던 것과 같은 이유다. 한 카드에 수천 줄이
     * 쌓인 상태에서 전부 읽으면 그만큼 메모리에 올라간다. 그리고 창이 길어질수록 피처의
     * 뜻도 흐려진다. {@code amountToMedian} 은 <b>요즘</b> 씀씀이와 견주는 값이지 평생 평균과
     * 견주는 값이 아니다.
     *
     * <p>최신순으로 받아 뒤집는다. 오래된 순으로 받으면 상한에 걸렸을 때 <b>가장 최근 건이
     * 빠진다.</b> 그건 이번 판정에 제일 필요한 값이다.
     */
    List<CardTransaction> findByCardKeyAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
            String cardKey, Instant since, Pageable pageable);

    /** 이미 담은 주문인지. 아웃박스 재배달을 저장 전에 거른다. */
    boolean existsByOrderNo(String orderNo);
}
