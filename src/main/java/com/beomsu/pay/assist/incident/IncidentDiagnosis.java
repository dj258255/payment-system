package com.beomsu.pay.assist.incident;

/**
 * 진단 하나.
 *
 * @param cause    고른 원인 유형
 * @param evidence <b>로그에서 인용한 한 줄.</b> 인용을 강제하는 이유는, 근거 없이 유형만 내면
 *                 사람이 그 판단을 확인할 방법이 없어서다. 상담 초안에서 숫자 출처를 강제한 것과 같은 결
 */
public record IncidentDiagnosis(IncidentCause cause, String evidence) {
}
