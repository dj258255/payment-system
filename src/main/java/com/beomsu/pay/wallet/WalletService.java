package com.beomsu.pay.wallet;

import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월렛 애플리케이션 서비스 — wallet 모듈의 공개 진입점.
 *
 * <p>충전({@link #charge})·사용({@link #use})·환불({@link #refund})은 각각 {@link WalletAccount}의
 * 잔액 변경 + {@link WalletTransaction} 이력 append를 하나의 트랜잭션으로 수행한다.
 *
 * <p><b>동시성</b>: 낙관적 락(@Version) 충돌 시 최대 {@value #MAX_RETRY}회 재시도한다. 충돌은
 * {@code saveAndFlush} 시점에 감지되며, 재시도 루프를 인라인해 자기호출 프록시 우회 문제를 피한다
 * (order의 StockDeductionService.deductOptimisticWithRetry와 동일한 방식). 이 기법의 동시 차감 정합성
 * (이중차감·마이너스 잔액 0건)은 동일 원자 차감을 쓰는 {@code StockLockComparisonTest}에서 실스레드로
 * 실측 검증돼 있다. idempotency는 상위(멱등키)에서 관리한다고 보고 여기서는 다루지 않는다.
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    private static final int MAX_RETRY = 5;

    private final WalletAccountRepository accountRepository;
    private final WalletTransactionRepository transactionRepository;

    /** 선불 충전 — 전금법 기명 한도 초과 시 LIMIT_EXCEEDED. 낙관적 락 충돌은 재시도. */
    public long charge(long userId, long amount) {
        return mutateWithRetry(userId, WalletTransactionType.CHARGE, amount, WalletAccount::charge);
    }

    /** 잔액 차감(결제) — 부족 시 INSUFFICIENT_BALANCE. 낙관적 락 충돌은 재시도. */
    public long use(long userId, long amount) {
        return mutateWithRetry(userId, WalletTransactionType.USE, amount, WalletAccount::use);
    }

    /** 환불 — 취소된 결제의 차감분을 되돌린다. 낙관적 락 충돌은 재시도. */
    public long refund(long userId, long amount) {
        return mutateWithRetry(userId, WalletTransactionType.REFUND, amount, WalletAccount::refund);
    }

    /** 잔액 조회 — 계정이 없으면 0. */
    @Transactional(readOnly = true)
    public long balance(long userId) {
        return accountRepository.findById(userId)
                .map(WalletAccount::getBalance)
                .orElse(0L);
    }

    /** 잔액 + 최근 거래 이력 조회 — 사용자 월렛 화면용. */
    @Transactional(readOnly = true)
    public WalletView myWallet(long userId) {
        return WalletView.of(balance(userId), transactionRepository.findTop20ByUserIdOrderByIdDesc(userId));
    }

    @FunctionalInterface
    private interface BalanceMutation {
        void apply(WalletAccount account, long amount);
    }

    /**
     * 잔액 변경 + 이력 append를 낙관적 락 재시도와 함께 수행한다. 재시도 루프를 단일 {@code @Transactional}로
     * 감싸지 않는다 — 감싸면 첫 충돌에서 트랜잭션이 rollback-only가 되어 재시도가 무의미해지고, 자기호출이라
     * 프록시도 우회된다. 대신 각 시도의 repository 연산(findById/saveAndFlush/save)이 Spring Data 기본
     * 트랜잭션 경계로 처리되며, @Version 충돌은 saveAndFlush 시점에 감지되어 다음 시도로 넘어간다.
     * 재시도를 소진하면 예외.
     */
    private long mutateWithRetry(long userId, WalletTransactionType type, long amount, BalanceMutation mutation) {
        int attempts = 0;
        while (true) {
            try {
                WalletAccount account = accountRepository.findById(userId)
                        .orElseGet(() -> WalletAccount.of(userId));
                mutation.apply(account, amount); // 한도/잔액 검증은 엔티티가 수행
                accountRepository.saveAndFlush(account); // 버전 충돌을 이 시점에 감지
                transactionRepository.save(WalletTransaction.of(userId, type, amount, account.getBalance()));
                return account.getBalance();
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
                if (++attempts >= MAX_RETRY) {
                    throw new WalletException("WALLET_CONCURRENCY",
                            "월렛 잔액 변경 경합이 계속됩니다: userId=" + userId);
                }
            }
        }
    }
}
