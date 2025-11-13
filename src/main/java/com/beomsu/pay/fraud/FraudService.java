package com.beomsu.pay.fraud;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FDS 룰 엔진 — 결제 요청의 위험 점수를 매겨 판정한다.
 *
 * <p>여러 룰(velocity, 금액 이상치, 블랙리스트)의 가중치를 합산해 점수를 내고, 구간별로
 * ALLOW/CHALLENGE/REVIEW/BLOCK을 결정한다. <b>임계값·가중치는 프로퍼티로 주입</b>돼 코드 배포 없이
 * 조정할 수 있다(무배포 룰 변경). 블랙리스트도 런타임에 추가/제거된다.
 *
 * <p>이 서비스는 <b>동기 인라인 판정</b>(결제 경로에서 빠르게)에 쓰도록 경량 룰만 담는다.
 * 무거운 분석(그래프·ML)은 비동기 사후 탐지로 분리하는 것이 정석이다 — 이 판단이 이 기능의 핵심.
 */
@Service
@RequiredArgsConstructor
public class FraudService {

    // --- 무배포 조정 가능한 룰 파라미터 (프로퍼티 주입) ---
    @Value("${fds.velocity.threshold:5}")
    private int velocityThreshold;          // 1분 내 시도 횟수 임계
    @Value("${fds.velocity.weight:40}")
    private int velocityWeight;
    @Value("${fds.amount.threshold:1000000}")
    private long amountThreshold;           // 고액 임계
    @Value("${fds.amount.weight:30}")
    private int amountWeight;
    @Value("${fds.blacklist.weight:100}")
    private int blacklistWeight;
    // 점수 구간 임계 (BLOCK >= 100, REVIEW >= 60, CHALLENGE >= 40)
    @Value("${fds.decision.block:100}")
    private int blockThreshold;
    @Value("${fds.decision.review:60}")
    private int reviewThreshold;
    @Value("${fds.decision.challenge:40}")
    private int challengeThreshold;

    private final VelocityCounter velocityCounter;
    private final Set<String> cardBlacklist = ConcurrentHashMap.newKeySet();

    /** 블랙리스트에 카드 추가(런타임, 무배포). */
    public void blacklistCard(String cardKey) {
        cardBlacklist.add(cardKey);
    }

    public FraudResult evaluate(FraudCheckRequest req) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        // 룰 1: 블랙리스트 (즉시 BLOCK 수준 가중치)
        if (cardBlacklist.contains(req.cardKey())) {
            score += blacklistWeight;
            reasons.add("BLACKLISTED_CARD");
        }

        // 룰 2: velocity — 카드 키 기준 1분 내 시도 횟수
        int attempts = velocityCounter.recordAndCount("card:" + req.cardKey());
        if (attempts > velocityThreshold) {
            score += velocityWeight;
            reasons.add("VELOCITY_EXCEEDED(" + attempts + ")");
        }

        // 룰 3: 금액 이상치
        if (req.amount() > amountThreshold) {
            score += amountWeight;
            reasons.add("HIGH_AMOUNT");
        }

        return new FraudResult(score, decide(score), reasons);
    }

    private FdsDecision decide(int score) {
        if (score >= blockThreshold) return FdsDecision.BLOCK;
        if (score >= reviewThreshold) return FdsDecision.REVIEW;
        if (score >= challengeThreshold) return FdsDecision.CHALLENGE;
        return FdsDecision.ALLOW;
    }
}
