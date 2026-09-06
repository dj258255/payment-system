package com.beomsu.pay.seller.screening;

/**
 * 이름 말고 함께 보는 것들 — <b>생년월일과 국적</b>.
 *
 * <p><b>왜 만들었나</b>: 임계를 고르려고 이름 점수를 쟀더니 <b>같은 사람의 다른 로마자 표기와
 * 아예 다른 사람이 같은 점수</b>를 받았다. 그때는 그것을 "문자열 거리의 한계"로 적고 사람
 * 검토로 보냈다. 그런데 실제 제재 스크리닝은 이름만으로 하지 않는다 —
 * 정확 일치, 퍼지 매칭, 그리고 <b>생년월일·국적·문서번호로 후보를 좁히는 층</b>이 따로 있다.
 * 경보의 90% 이상이 오탐이라 그 세 번째 층이 실무의 핵심인데,
 * <b>내가 만든 것은 두 번째 층까지였다.</b>
 *
 * <p>그러니 못 가른 이유는 거리 계산이 약해서가 아니라 <b>볼 것을 안 보고 있어서</b>다.
 * 명단 항목이 이름·프로그램·국가만 들고 있어 애초에 좁힐 재료가 없었다.
 *
 * <h3>모르는 것은 점수를 안 건드린다</h3>
 * 우리가 판매자의 생년월일을 모르거나 명단이 안 주면 <b>이름 점수를 그대로 둔다.</b>
 * 모르는 것을 "안 맞았다"로 읽으면 제재 대상이 조용히 통과한다 —
 * 이 시스템이 미확정 결제를 실패로 안 적는 것과 같은 이유다.
 */
public record SecondaryIdentifiers(String birthDate, String nationality) {

    /** 어긋났을 때 깎는 폭. 임계(80)를 확실히 넘겨 떨어뜨리도록 잡았다. */
    private static final int DOB_MISMATCH = -40;
    private static final int NATIONALITY_MISMATCH = -15;
    /** 맞았을 때 올리는 폭. 깎는 쪽보다 작다 — <b>맞다는 증거보다 아니라는 증거가 세다.</b> */
    private static final int DOB_MATCH = 10;
    private static final int NATIONALITY_MATCH = 5;

    public static SecondaryIdentifiers of(String birthDate, String nationality) {
        return new SecondaryIdentifiers(blankToNull(birthDate), blankToNull(nationality));
    }

    public static SecondaryIdentifiers unknown() {
        return new SecondaryIdentifiers(null, null);
    }

    /**
     * 이름 점수를 2차 식별자로 보정한다.
     *
     * @param nameScore 이름만으로 낸 0~100
     * @param ours      우리가 아는 판매자의 식별자
     * @param theirs    명단 항목의 식별자
     */
    public static int adjust(int nameScore, SecondaryIdentifiers ours, SecondaryIdentifiers theirs) {
        int score = nameScore;
        score += compare(ours.birthDate(), theirs.birthDate(), DOB_MATCH, DOB_MISMATCH);
        score += compare(ours.nationality(), theirs.nationality(), NATIONALITY_MATCH, NATIONALITY_MISMATCH);
        return Math.max(0, Math.min(100, score));
    }

    /** 한쪽이라도 모르면 0 — 점수를 안 건드린다. */
    private static int compare(String a, String b, int onMatch, int onMismatch) {
        if (a == null || b == null) return 0;
        return a.equalsIgnoreCase(b) ? onMatch : onMismatch;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
