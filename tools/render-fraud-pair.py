#!/usr/bin/env python3
"""초안 둘을 사람이 읽을 모양으로 낸다.

<b>어느 쪽이 모델인지 안 밝힌다.</b> 서버가 A·B 로만 주고 순서를 건마다 뒤집는데,
여기서 이름을 붙이면 그 장치가 무의미해진다. 알고 고치면 편집량이 그쪽으로 끌린다.

`json.tool` 은 기본이 ASCII 이스케이프라 한글이 \\uce74\\ub4dc 로 나온다. 읽는 데 시간이
걸리고 열두 건이면 그만큼 늘어진다.
"""
import json
import sys


def main() -> int:
    raw = sys.stdin.read().strip()
    if not raw:
        print("    (초안이 안 나왔다. 모델이 떠 있는지 본다)")
        return 1
    try:
        d = json.loads(raw)
    except json.JSONDecodeError:
        print("    (초안 응답이 JSON 이 아니다)")
        return 1

    for key in ("a", "b"):
        text = d.get(key)
        print()
        print(f"  ── {key.upper()} " + "─" * 56)
        if not text:
            print("    (비어 있다)")
            continue
        for line in text.rstrip().split("\n"):
            print(f"    {line}" if line else "")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
