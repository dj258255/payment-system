package com.beomsu.pay.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DLQ 백오피스 어드민 — 죽은 메시지를 조회하고 재처리한다.
 *
 * <p>"결제는 만들고 나서가 진짜"라는 운영 감각의 도구. 알림 발송이 일시 장애로 DLQ에 쌓인 뒤,
 * 채널이 복구되면 운영자가 재처리한다. 재처리 성공 시 DLQ에서 제거하고 처리 완료로 마킹하며,
 * 다시 실패하면 재시도 횟수만 올려 DLQ에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class NotificationAdminService {

    private static final String CONSUMER = "notification";

    private final DeadLetterRepository deadLetters;
    private final ProcessedEventRepository processedEvents;
    private final NotificationSender sender;

    @Transactional(readOnly = true)
    public Page<DeadLetterView> listDeadLetters(Pageable pageable) {
        return deadLetters.findAll(pageable)
                .map(d -> new DeadLetterView(d.getId(), d.getEventType(), d.getEventKey(),
                        d.getOrderNo(), d.getPaymentId(), d.getAmount(), d.getFailReason(),
                        d.getRetryCount(), d.getCreatedAt()));
    }

    /** DLQ 항목을 재처리한다. 성공하면 제거+완료 마킹, 실패하면 재시도 횟수만 올린다. */
    @Transactional
    public boolean reprocess(Long deadLetterId) {
        DeadLetter dl = deadLetters.findById(deadLetterId)
                .orElseThrow(() -> new IllegalArgumentException("DLQ 항목 없음: " + deadLetterId));
        try {
            sender.sendPaymentReceipt(dl.getOrderNo(), dl.getPaymentId(), dl.getAmount());
            processedEvents.save(ProcessedEvent.of(dl.getEventKey(), CONSUMER));
            deadLetters.delete(dl);
            return true;
        } catch (RuntimeException ex) {
            dl.incrementRetry();   // 여전히 실패 — DLQ에 남기고 재시도 횟수만 증가
            return false;
        }
    }
}
