package com.beomsu.pay.assist;

import org.springframework.stereotype.Component;

/**
 * {@link FactPack} 을 프롬프트로 만든다. <b>어댑터가 어느 모델이든 같은 프롬프트를 쓴다.</b>
 *
 * <p>왜 분리했나: 모델을 바꿨는데 결과가 달라지면 <b>모델 때문인지 프롬프트 때문인지</b>
 * 알 수 없다. 프롬프트를 어댑터마다 두면 그 구분이 영영 안 된다.
 * 부하 테스트에서 어댑터 구현 차이가 측정에 섞이면 안 되는 것과 같은 이유다.
 *
 * <p><b>지시가 곧 방어선의 일부다.</b> 여기서 "주어진 사실 밖의 숫자를 쓰지 말라"고 못 박고,
 * {@link NumberGuard} 가 지켜졌는지 <b>기계로</b> 확인한다. 지시만 있고 검사가 없으면
 * 모델이 지킬 때만 지켜지고, 검사만 있고 지시가 없으면 반려율만 높아진다. 둘 다 필요하다.
 * Uber Genie 가 "주어진 sub-context 안에서만 답하고 출처를 인용하라"를 명시적으로
 * 지시하는 것과 같은 자리다.
 */
@Component
public class PromptBuilder {

    /** 모델에게 주는 역할과 금지사항. 사실 목록은 여기 없다 — 아래에서 붙인다. */
    private static final String SYSTEM = """
            당신은 결제사 고객센터 상담원을 돕는 보조자입니다.
            상담원이 고객에게 보낼 답변의 **초안**을 씁니다.

            반드시 지킬 것:
            1. 아래 [확인된 사실] 에 있는 내용만 쓰십시오.
            2. **금액과 날짜는 [인용해도 되는 금액]·[인용해도 되는 날짜] 에 있는 값만** 쓰십시오.
               그 목록은 <표기 허용 목록>이지 잔액·청구액이 아닙니다. 목록 자체를 설명하지 마십시오.
               계산하지 마십시오. 합계·차액·수수료를 직접 구하지 마십시오.
               사실에 없는 숫자를 쓰면 그 초안은 폐기됩니다.
            3. 원인이 적혀 있으면 "확인되었다"가 아니라 "확인 중"으로 쓰십시오.
               원인은 담당자가 확정합니다.
            4. 사과나 보상을 약속하지 마십시오. 그건 상담원이 판단합니다.
            5. 한국어 존댓말로, 5문장 이내로 씁니다.

            모르는 것은 쓰지 말고 비워 두십시오. 빈 초안이 지어낸 초안보다 낫습니다.
            """;

    /** 시스템 프롬프트 — 모델이 무엇을 하는 사람인지. */
    public String system() {
        return SYSTEM;
    }

    /**
     * 사실 묶음을 사용자 프롬프트로. <b>여기서 계산하지 않는다</b> —
     * 넘기는 것은 이미 코드가 낸 값들뿐이다.
     */
    public String user(FactPack facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("[주문번호]\n").append(facts.orderNo()).append("\n\n");

        sb.append("[확인된 사실]\n");
        for (String f : facts.facts()) {
            sb.append("- ").append(f).append('\n');
        }

        if (!facts.amounts().isEmpty()) {
            // 레이블이 중요하다. 처음엔 "[쓸 수 있는 금액]"이라고 썼다가
            // 모델이 그걸 <잔액>으로 읽고 "사용 가능한 금액은 10,000원과 270원입니다"라고 썼다.
            // 목록의 성격(인용 허용 목록)을 이름에 박아야 한다.
            sb.append("\n[인용해도 되는 금액] — 사실이 아니라 <표기 허용 목록>입니다.\n")
              .append("이 목록의 값 외의 금액은 쓰지 마십시오. 잔액이나 청구액이 아닙니다.\n");
            facts.amounts().stream().sorted()
                    .forEach(a -> sb.append("- ").append(String.format("%,d", a)).append("원\n"));
        }
        if (!facts.dates().isEmpty()) {
            sb.append("\n[인용해도 되는 날짜] — 표기 허용 목록입니다.\n")
              .append("이 목록의 값 외의 날짜는 쓰지 마십시오.\n");
            facts.dates().stream().sorted()
                    .forEach(d -> sb.append("- ").append(d).append('\n'));
        }
        if (facts.causeHint() != null && !facts.causeHint().isBlank()) {
            sb.append("\n[규칙이 추정한 원인] (확정 아님 — 확인 중으로 쓰십시오)\n")
              .append(facts.causeHint()).append('\n');
        }
        if (!facts.complete()) {
            sb.append("\n[주의] 일부 시스템 기록을 가져오지 못했습니다. ")
              .append("이 사실을 초안에 밝히십시오.\n");
        }
        sb.append("\n위 사실만으로 상담원용 초안을 작성하십시오.");
        return sb.toString();
    }
}
