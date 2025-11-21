package com.beomsu.pay.point;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포인트 애플리케이션 서비스 — point 모듈의 공개 진입점.
 *
 * <p>복합결제 Saga에서 order 모듈이 호출한다: 결제 시 {@link #use}(선점), 카드 실패 시
 * {@link #restore}(보상), 부분취소 시 {@link #refund}. 모든 잔액 변경은 이력 append와 함께 이뤄지며,
 * (orderNo, type)로 멱등성을 보장해 "따닥" 중복 요청·재시도에도 이중 차감/복원이 없도록 한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PointService {

    private final PointAccountRepository accountRepository;
    private final PointHistoryRepository historyRepository;

    /**
     * 포인트 차감(결제 선점). 같은 주문의 USE 이력이 이미 있으면 멱등하게 skip한다.
     * 롤백이 확실한 내부 자원이므로 복합결제에서 카드보다 먼저 선점된다.
     */
    public void use(long userId, long amount, String orderNo) {
        if (amount < 0) {
            throw new PointException("INVALID_AMOUNT", "포인트 금액은 음수일 수 없습니다: " + amount);
        }
        if (amount == 0) {
            return;
        }
        if (historyRepository.existsByOrderNoAndType(orderNo, PointHistoryType.USE)) {
            return; // 멱등: 이미 차감함
        }
        PointAccount account = accountRepository.findById(userId)
                .orElseGet(() -> PointAccount.of(userId, 0));
        account.use(amount); // 잔액 부족이면 여기서 INSUFFICIENT_POINT
        accountRepository.save(account);
        historyRepository.save(PointHistory.of(userId, PointHistoryType.USE, amount, orderNo));
    }

    /**
     * 포인트 복원(보상 트랜잭션). 카드 승인 실패 시 {@link #use}로 차감했던 포인트를 되돌린다.
     * 같은 주문의 RESTORE 이력이 이미 있으면 멱등하게 skip한다.
     */
    public void restore(long userId, long amount, String orderNo) {
        if (amount < 0) {
            throw new PointException("INVALID_AMOUNT", "포인트 금액은 음수일 수 없습니다: " + amount);
        }
        if (amount == 0) {
            return;
        }
        if (historyRepository.existsByOrderNoAndType(orderNo, PointHistoryType.RESTORE)) {
            return; // 멱등: 이미 복원함
        }
        PointAccount account = accountRepository.findById(userId)
                .orElseGet(() -> PointAccount.of(userId, 0));
        account.restore(amount);
        accountRepository.save(account);
        historyRepository.save(PointHistory.of(userId, PointHistoryType.RESTORE, amount, orderNo));
    }

    /**
     * 포인트 환불(부분취소). 취소된 결제에서 포인트로 결제했던 몫을 되돌린다.
     * 부분취소는 한 주문에 여러 번 발생할 수 있으므로 REFUND는 멱등 skip하지 않는다.
     */
    public void refund(long userId, long amount, String orderNo) {
        if (amount < 0) {
            throw new PointException("INVALID_AMOUNT", "포인트 금액은 음수일 수 없습니다: " + amount);
        }
        if (amount == 0) {
            return;
        }
        PointAccount account = accountRepository.findById(userId)
                .orElseGet(() -> PointAccount.of(userId, 0));
        account.restore(amount);
        accountRepository.save(account);
        historyRepository.save(PointHistory.of(userId, PointHistoryType.REFUND, amount, orderNo));
    }

    /**
     * 이미 환불된 몫을 제외한, 취소 시 환불 가능한 포인트. 이 주문의 USE 합에서 REFUND 합을 뺀다.
     * 음수면(방어) 0으로 보정한다.
     */
    @Transactional(readOnly = true)
    public long refundableAmount(String orderNo) {
        long used = historyRepository.sumAmountByOrderNoAndType(orderNo, PointHistoryType.USE);
        long refunded = historyRepository.sumAmountByOrderNoAndType(orderNo, PointHistoryType.REFUND);
        return Math.max(0, used - refunded);
    }

    /** 잔액 조회 — 계정이 없으면 0. */
    @Transactional(readOnly = true)
    public long balance(long userId) {
        return accountRepository.findById(userId)
                .map(PointAccount::getBalance)
                .orElse(0L);
    }
}
