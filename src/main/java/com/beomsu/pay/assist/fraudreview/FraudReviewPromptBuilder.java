package com.beomsu.pay.assist.fraudreview;

import com.beomsu.pay.fraud.FraudReviewFacts;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * 프롬프트를 만든다. <b>사실은 여기서 다 넣고, 모델에게는 문장만 시킨다.</b>
 *
 * <p><b>판정을 시키지 않는 것이 이 프롬프트의 요점이다.</b> "이 건이 부정거래인지 판단하라"고
 * 시키면 모델은 반드시 답을 낸다. 근거가 없어도 낸다. 상황 3.3 에서 확인한 그대로다 —
 * 근거 없이 단정한 답이 출력 검증 다섯을 전부 통과했고, <b>맞은 답보다 틀린 답의 신뢰도가
 * 높았다.</b> 그래서 여기서는 심사자가 무엇을 봐야 하는지를 정리하는 데까지만 시킨다.
 *
 * <p><b>숫자는 준 것만 쓰게 한다.</b> 그래도 지어내면
 * {@code assist.draft.NumericProvenanceGuard} 가 초안을 버린다. 프롬프트는 1차 방어이고
 * 가드가 2차다. 프롬프트만으로 막힌다고 보면 안 된다.
 */
@Component
public class FraudReviewPromptBuilder {

    private static final NumberFormat WON = NumberFormat.getIntegerInstance(Locale.KOREA);

    String system() {
        return """
               너는 결제 이상거래 심사자를 돕는 보조다. 심사자가 승인·거부를 누르기 전에
               읽을 짧은 정리를 쓴다.

               지켜야 할 것
               1. 아래 <사실> 에 있는 숫자만 쓴다. 없는 금액·비율·건수를 만들지 마라.
               2. 부정거래인지 아닌지 판단하지 마라. "정상으로 보인다", "차단해야 한다" 같은
                  결론을 쓰면 안 된다. 무엇을 확인해야 하는지까지만 쓴다.
               3. 사실에 없는 정보를 추측해 채우지 마라. 모르는 것은 모른다고 쓴다.
               4. 다섯 문장 이내. 표나 목록 없이 줄글로 쓴다.
               5. 한국어로 쓴다.
               """;
    }

    String user(FraudReviewFacts f) {
        StringBuilder sb = new StringBuilder();
        sb.append("<사실>\n");
        sb.append("주문번호: ").append(f.orderNo()).append('\n');
        sb.append("결제 금액: ").append(WON.format(f.amount())).append("원\n");
        sb.append("카드: ").append(f.maskedCardKey()).append('\n');
        sb.append("규칙 점수: ").append(f.score()).append("점, 등급: ").append(f.decision()).append('\n');

        sb.append("발동한 규칙:\n");
        for (var r : f.firedRules()) {
            sb.append("  - ").append(r.name());
            if (r.detail() != null) {
                sb.append(" (값 ").append(r.detail()).append(')');
            }
            if (r.normalRatio() != null) {
                sb.append(": 최근 판정 ").append(r.judged()).append("건 중 ")
                  .append(Math.round(r.normalRatio() * 100)).append("%가 정상으로 닫힘");
            } else {
                // 얇은 표본을 비율로 주면 모델이 그것을 근거처럼 쓴다. 건수만 준다.
                sb.append(": 판정 표본 ").append(r.judged()).append("건. 비율을 낼 만큼 쌓이지 않음");
            }
            sb.append('\n');
        }

        sb.append("같은 카드의 지난 심사: ");
        if (f.sameCardJudged() == 0) {
            sb.append("판정이 끝난 건이 없음\n");
        } else {
            sb.append(f.sameCardJudged()).append("건 중 ")
              .append(f.sameCardApproved()).append("건이 정상으로 닫힘\n");
        }
        sb.append("</사실>\n\n");
        sb.append("위 사실로 심사자가 무엇을 확인해야 하는지 정리해라.");
        return sb.toString();
    }
}
