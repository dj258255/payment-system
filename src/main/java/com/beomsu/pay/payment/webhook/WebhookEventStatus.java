package com.beomsu.pay.payment.webhook;

/** 웹훅 이벤트 처리 상태. */
public enum WebhookEventStatus {

    /** 수신·저장 완료. 아직 해석(조회 재검증) 전. */
    RECEIVED,

    /** 조회 API로 실상태를 재검증해 처리 완료. */
    PROCESSED,

    /** 처리 대상이 아니라 건너뜀(예: paymentKey 없음, 중복 수신). */
    SKIPPED,

    /** 처리 중 오류 — 다음 주기(폴링/대사)에서 재처리 대상. */
    FAILED
}
