package com.beomsu.pay.subscription;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class SubscriptionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 5);

    private FakeBillingGateway gateway;
    private SubscriptionRepository subscriptionRepository;
    private BillingKeyRepository billingKeyRepository;
    private DunningAttemptRepository dunningAttemptRepository;
    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        gateway = new FakeBillingGateway();
        subscriptionRepository = mock(SubscriptionRepository.class);
        billingKeyRepository = mock(BillingKeyRepository.class);
        dunningAttemptRepository = mock(DunningAttemptRepository.class);
        service = new SubscriptionService(gateway, subscriptionRepository, billingKeyRepository, dunningAttemptRepository);
    }

    private Subscription activeSub() {
        return Subscription.create(1L, "billing-1", 10_000,
                TODAY.minusMonths(1), TODAY); // 오늘이 청구일
    }

    private void givenBillingTargets(Subscription... subs) {
        when(subscriptionRepository.findByStatusInAndNextBillingDateLessThanEqual(any(), any()))
                .thenReturn(List.of(subs));
    }

    private DunningAttempt capturedDunning() {
        ArgumentCaptor<DunningAttempt> captor = ArgumentCaptor.forClass(DunningAttempt.class);
        verify(dunningAttemptRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("(1) SUCCESS → renew + nextBillingDate 한 달 뒤로 갱신, SUCCESS 이력 기록")
    void successRenews() {
        Subscription sub = activeSub();
        givenBillingTargets(sub);
        when(dunningAttemptRepository.countBySubscriptionIdAndResult(any(), any())).thenReturn(0);
        gateway.setNextResult(BillingResult.SUCCESS);

        int processed = service.runBillingCycle(TODAY);

        assertThat(processed).isEqualTo(1);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getNextBillingDate()).isEqualTo(TODAY.plusMonths(1));
        assertThat(capturedDunning().getResult()).isEqualTo(BillingResult.SUCCESS);
        assertThat(capturedDunning().getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("(2) SOFT_DECLINE 첫 실패 → IN_GRACE_PERIOD, nextRetryAt(오늘+2일) 기록")
    void softDeclineFirstEntersGrace() {
        Subscription sub = activeSub();
        givenBillingTargets(sub);
        when(dunningAttemptRepository.countBySubscriptionIdAndResult(any(), any())).thenReturn(0);
        gateway.setNextResult(BillingResult.SOFT_DECLINE);

        service.runBillingCycle(TODAY);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.IN_GRACE_PERIOD);
        assertThat(sub.getNextBillingDate()).isEqualTo(TODAY); // 유예 중 청구일 불변
        DunningAttempt dunning = capturedDunning();
        assertThat(dunning.getResult()).isEqualTo(BillingResult.SOFT_DECLINE);
        assertThat(dunning.getNextRetryAt()).isEqualTo(TODAY.plusDays(2));
        assertThat(dunning.getAttemptNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("(3) HARD_DECLINE → 즉시 ON_HOLD, 재시도 스케줄 없음(nextRetryAt=null)")
    void hardDeclineImmediateHold() {
        Subscription sub = activeSub();
        givenBillingTargets(sub);
        when(dunningAttemptRepository.countBySubscriptionIdAndResult(any(), any())).thenReturn(0);
        gateway.setNextResult(BillingResult.HARD_DECLINE);

        service.runBillingCycle(TODAY);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ON_HOLD);
        DunningAttempt dunning = capturedDunning();
        assertThat(dunning.getResult()).isEqualTo(BillingResult.HARD_DECLINE);
        assertThat(dunning.getNextRetryAt()).isNull(); // 재시도 예약 없음
    }

    @Test
    @DisplayName("(4) SOFT_DECLINE 재시도 소진(3회째 실패) → ON_HOLD, 재시도 없음")
    void softDeclineExhaustedHolds() {
        Subscription sub = activeSub();
        sub.enterGrace(); // 이미 유예 중(앞선 실패들로 진입)
        givenBillingTargets(sub);
        // 앞서 2번 soft decline → 이번이 3번째 = MAX_ATTEMPTS 도달
        when(dunningAttemptRepository.countBySubscriptionIdAndResult(any(), any())).thenReturn(2);
        gateway.setNextResult(BillingResult.SOFT_DECLINE);

        service.runBillingCycle(TODAY);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ON_HOLD);
        DunningAttempt dunning = capturedDunning();
        assertThat(dunning.getResult()).isEqualTo(BillingResult.SOFT_DECLINE);
        assertThat(dunning.getNextRetryAt()).isNull();
        assertThat(dunning.getAttemptNo()).isEqualTo(3);
    }

    @Test
    @DisplayName("SUCCESS: 유예 중이던 구독은 recover 후 ACTIVE로 복귀한다")
    void successRecoversFromGrace() {
        Subscription sub = activeSub();
        sub.enterGrace();
        givenBillingTargets(sub);
        when(dunningAttemptRepository.countBySubscriptionIdAndResult(any(), any())).thenReturn(1);
        gateway.setNextResult(BillingResult.SUCCESS);

        service.runBillingCycle(TODAY);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getNextBillingDate()).isEqualTo(TODAY.plusMonths(1));
    }

    @Test
    @DisplayName("subscribe: 빌링키를 저장하고 첫 청구일을 한 달 뒤로 잡은 ACTIVE 구독을 만든다")
    void subscribeCreatesActive() {
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Subscription sub = service.subscribe(1L, "billing-1", 10_000, TODAY);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getNextBillingDate()).isEqualTo(TODAY.plusMonths(1));

        ArgumentCaptor<BillingKey> captor = ArgumentCaptor.forClass(BillingKey.class);
        verify(billingKeyRepository).save(captor.capture());
        assertThat(captor.getValue().getBillingKey()).isEqualTo("billing-1");
        // customerKey는 UUID(무작위) — 예측 가능한 값이 아니어야 한다
        assertThat(captor.getValue().getCustomerKey()).hasSize(36);
    }

    @Test
    @DisplayName("changePlan: proration 정산액을 반환하고 planAmount를 갱신한다")
    void changePlanProrates() {
        Subscription sub = Subscription.create(1L, "billing-1", 10_000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        when(subscriptionRepository.findById(anyLong())).thenReturn(java.util.Optional.of(sub));

        // 15일 남음, 차액 20000 → 10000 추가 청구(업그레이드)
        long proration = service.changePlan(1L, 30_000, LocalDate.of(2026, 1, 16));

        assertThat(proration).isEqualTo(10_000);
        assertThat(sub.getPlanAmount()).isEqualTo(30_000);
    }
}
