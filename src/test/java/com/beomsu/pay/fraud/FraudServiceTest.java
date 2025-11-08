package com.beomsu.pay.fraud;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class FraudServiceTest {

    private VelocityCounter velocityCounter;
    private FraudService service;

    @BeforeEach
    void setUp() {
        velocityCounter = mock(VelocityCounter.class);
        service = new FraudService(velocityCounter);
        // @Value 기본값을 테스트에서 명시적으로 설정 (무배포 조정 파라미터)
        ReflectionTestUtils.setField(service, "velocityThreshold", 5);
        ReflectionTestUtils.setField(service, "velocityWeight", 40);
        ReflectionTestUtils.setField(service, "amountThreshold", 1_000_000L);
        ReflectionTestUtils.setField(service, "amountWeight", 30);
        ReflectionTestUtils.setField(service, "blacklistWeight", 100);
        ReflectionTestUtils.setField(service, "blockThreshold", 100);
        ReflectionTestUtils.setField(service, "reviewThreshold", 60);
        ReflectionTestUtils.setField(service, "challengeThreshold", 40);
    }

    private FraudCheckRequest req(long amount) {
        return new FraudCheckRequest(1L, "card-1", "1.2.3.4", "device-1", amount);
    }

    @Test
    @DisplayName("정상: 낮은 velocity + 소액 → ALLOW (점수 0)")
    void allow() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(1);

        FraudResult r = service.evaluate(req(10_000));

        assertThat(r.decision()).isEqualTo(FdsDecision.ALLOW);
        assertThat(r.score()).isZero();
    }

    @Test
    @DisplayName("velocity 초과(6>5) → +40 → CHALLENGE")
    void velocityExceededChallenges() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(6);

        FraudResult r = service.evaluate(req(10_000));

        assertThat(r.decision()).isEqualTo(FdsDecision.CHALLENGE);
        assertThat(r.reasons()).anyMatch(s -> s.startsWith("VELOCITY_EXCEEDED"));
    }

    @Test
    @DisplayName("고액만(+30)은 임계 미만 → ALLOW")
    void highAmountAloneAllows() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(1);

        FraudResult r = service.evaluate(req(2_000_000));

        assertThat(r.score()).isEqualTo(30);
        assertThat(r.decision()).isEqualTo(FdsDecision.ALLOW);
    }

    @Test
    @DisplayName("velocity + 고액(40+30=70) → REVIEW")
    void velocityPlusAmountReviews() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(6);

        FraudResult r = service.evaluate(req(2_000_000));

        assertThat(r.score()).isEqualTo(70);
        assertThat(r.decision()).isEqualTo(FdsDecision.REVIEW);
    }

    @Test
    @DisplayName("블랙리스트 카드(+100) → BLOCK")
    void blacklistBlocks() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(1);
        service.blacklistCard("card-1");

        FraudResult r = service.evaluate(req(10_000));

        assertThat(r.decision()).isEqualTo(FdsDecision.BLOCK);
        assertThat(r.isBlocked()).isTrue();
        assertThat(r.reasons()).contains("BLACKLISTED_CARD");
    }
}
