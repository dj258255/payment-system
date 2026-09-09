#!/usr/bin/env python3
"""심사 한 건의 사실을 사람이 읽을 모양으로 낸다.

원문 JSON 을 그대로 뿌리면 읽는 데 시간이 걸리고, 열두 건이면 그만큼 늘어난다.
심사자가 메모를 쓰기 전에 보는 화면이라 여기서 시간을 쓰면 판정 전체가 늘어진다.

<b>초안은 안 낸다.</b> 이 자리에서 초안을 같이 보여 주면 사람 답이 초안을 닮아
두 편집률의 차이가 방식 차이가 아니게 된다.
"""
import json
import sys


def main() -> int:
    raw = sys.stdin.read().strip()
    if not raw:
        print("    (사실을 못 읽었다. 앱이 떠 있는지 본다)")
        return 1
    try:
        d = json.loads(raw)
    except json.JSONDecodeError:
        print("    (사실 응답이 JSON 이 아니다)")
        return 1

    won = f"{d['amount']:,}"
    print()
    print(f"    주문 {d['orderNo']} · 결제 {won}원 · 카드 {d['maskedCardKey']}")
    print(f"    규칙 점수 {d['score']}점 → {d['decision']}")
    print()
    print("    발동한 규칙")
    for r in d.get("firedRules", []):
        detail = f"({r['detail']})" if r.get("detail") else ""
        ratio = r.get("normalRatio")
        if ratio is None:
            tail = f"판정 표본 {r['judged']}건이라 비율을 안 낸다"
        else:
            tail = f"최근 심사 {r['judged']}건 중 {round(ratio * 100)}%가 정상으로 닫혔다"
        print(f"      · {r['name']}{detail} — {tail}")

    judged = d.get("sameCardJudged", 0)
    approved = d.get("sameCardApproved", 0)
    same = "없다" if judged == 0 else f"{judged}건 중 {approved}건이 정상으로 닫혔다"
    print()
    print(f"    같은 카드의 지난 심사: {same}")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
