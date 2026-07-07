package com.beomsu.pay.wallet;

import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** 선불 충전 — 전금법 기명 한도 초과 시 LIMIT_EXCEEDED. 낙관적 락 충돌은 재시도. 주문과 무관해 orderNo 없음. */
    public long charge(long userId, long amount) {
        return mutateWithRetry(userId, WalletTransactionType.CHARGE, amount, null, WalletAccount::charge);
    }

    /**
     * 잔액 차감(결제 예약) — 부족 시 INSUFFICIENT_BALANCE. 낙관적 락 충돌은 재시도.
     * {@code orderNo}로 <b>주문 단위 멱등</b>: 같은 주문의 USE가 이미 있으면 재차감하지 않고 현재 잔액을 반환한다
     * (사가 재진입·중복요청 대비). point의 orderNo 멱등과 동일한 계약.
     */
    public long use(long userId, long amount, String orderNo) {
        return mutateWithRetry(userId, WalletTransactionType.USE, amount, orderNo, WalletAccount::use);
    }

    /**
     * 환불(보상) — 취소된 결제의 차감분을 되돌린다. 낙관적 락 충돌은 재시도.
     * {@code orderNo}로 멱등: 같은 주문의 REFUND가 이미 있으면 이중환불하지 않는다(복구가 settle을 재실행해도 안전).
     */
    public long refund(long userId, long amount, String orderNo) {
        return mutateWithRetry(userId, WalletTransactionType.REFUND, amount, orderNo, WalletAccount::refund);
    }

    /** 주문에 예약(USE)된 월렛 차감액 — 없으면 0. 크래시 복구가 카드/포인트/월렛 몫을 역산할 때 쓴다. */
    @Transactional(readOnly = true)
    public long reservedAmountForOrder(String orderNo) {
        return transactionRepository.findByOrderNoAndType(orderNo, WalletTransactionType.USE)
                .map(WalletTransaction::getAmount)
                .orElse(0L);
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
     *
     * <p><b>주문 멱등</b>({@code orderNo != null}): 차감/환불을 두 번 반영하지 않기 위해 (1) 이력에 이미 같은
     * (orderNo, type)이 있으면 잔액 변경 없이 현재 잔액을 반환하고, (2) 그 사이 다른 스레드가 먼저 이력을 넣어
     * {@code (order_no, type)} 유니크 인덱스를 위반하면 이미 반영된 것으로 보고 멱등 skip한다. 사가 경로에서는
     * 같은 주문의 동시 차감이 주문 상태전이({@code Order.startPayment})로 상위에서 직렬화되므로, 잔액과 이력이
     * 원자적이지 않아도 이중반영은 일어나지 않는다 — 유니크 인덱스는 데이터 정합성의 최후 방어선이다.
     */
    private long mutateWithRetry(long userId, WalletTransactionType type, long amount,
                                 String orderNo, BalanceMutation mutation) {
        int attempts = 0;
        while (true) {
            try {
                if (orderNo != null && transactionRepository.existsByOrderNoAndType(orderNo, type)) {
                    return balance(userId); // 이미 반영됨 — 멱등 skip
                }
                WalletAccount account = accountRepository.findById(userId)
                        .orElseGet(() -> WalletAccount.of(userId));
                mutation.apply(account, amount); // 한도/잔액 검증은 엔티티가 수행
                accountRepository.saveAndFlush(account); // 버전 충돌을 이 시점에 감지
                transactionRepository.save(WalletTransaction.of(userId, type, amount, account.getBalance(), orderNo));
                return account.getBalance();
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
                if (++attempts >= MAX_RETRY) {
                    throw new WalletException("WALLET_CONCURRENCY",
                            "월렛 잔액 변경 경합이 계속됩니다: userId=" + userId);
                }
            } catch (DataIntegrityViolationException e) {
                // (order_no, type) 유니크 위반 = 같은 주문의 중복 차감/환불. 이미 반영됐으므로 멱등 skip.
                return balance(userId);
            }
        }
    }
}
