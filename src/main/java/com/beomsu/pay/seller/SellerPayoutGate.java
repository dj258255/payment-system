package com.beomsu.pay.seller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>이 판매자에게 돈을 내보내도 되는가.</b> 정산이 지급 직전에 묻는 자리다.
 *
 * <p><b>왜 정산이 판매자를 직접 안 읽나</b>: 읽게 하면 정산이 심사 규칙까지 알게 된다.
 * 규칙이 바뀔 때마다 두 모듈을 같이 고쳐야 하고, 모듈 경계가 이름만 남는다.
 * 그래서 판매자 쪽이 <b>예/아니오와 그 이유</b>만 돌려준다.
 *
 * <h3>모르는 것은 통과가 아니다</h3>
 * 판매자를 못 찾으면 <b>막는다.</b> 없는 판매자에게 돈이 나가는 것이 잘못된 보류보다 나쁘다.
 * 아직 심사 안 한 판매자({@code PENDING_SCREENING})도 막는다 —
 * <b>안 본 것과 통과는 다르다.</b> 이 시스템이 결제 미확정을 실패로 안 적는 것과 같은 이유다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerPayoutGate {

    private final SellerRepository sellers;

    /** @param sellerId {@code null} 이면 플랫폼 직판이라 대조할 판매자가 없다 */
    @Transactional(readOnly = true)
    public Decision check(Long sellerId) {
        if (sellerId == null) {
            return Decision.allowed("플랫폼 직판 — 외부로 나가는 지급이 아니다");
        }
        return sellers.findById(sellerId)
                .map(s -> s.getStatus().payable()
                        ? Decision.allowed("심사 통과")
                        : Decision.blocked("판매자 상태가 " + s.getStatus() + " 다"))
                .orElseGet(() -> {
                    // 없는 판매자에게 돈이 나가는 것이 잘못된 보류보다 나쁘다.
                    log.warn("[payout-gate] 판매자를 못 찾아 지급을 막는다 sellerId={}", sellerId);
                    return Decision.blocked("판매자를 찾지 못했다");
                });
    }

    /** @param allowed 내보내도 되는가 @param reason 왜 그렇게 판단했나. 막힌 이유는 화면에 그대로 나간다 */
    public record Decision(boolean allowed, String reason) {
        static Decision allowed(String reason) { return new Decision(true, reason); }
        static Decision blocked(String reason) { return new Decision(false, reason); }
    }
}
