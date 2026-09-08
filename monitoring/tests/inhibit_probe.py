#!/usr/bin/env python3
"""DB 장애 하나가 만든 알림 여섯 중 <몇 건이 실제로 사람에게 가는지> 잰다.

db-outage-probe 는 <b>규칙 단</b>의 수를 고정한다. 여섯이 뜨는 것까지다. 그런데 사람이
받는 것은 alertmanager 가 억제하고 남은 것이라, 규칙을 아무리 검사해도 그 수는 안 나온다.

<b>억제는 안 걸려도 조용하다.</b> 규칙이 안 울리면 사고 때 드러나는데, 억제가 안 걸리면
알림이 조금 더 올 뿐이라 영영 안 드러난다. 그래서 재는 장치가 따로 필요하다.

재는 법: 살아 있는 alertmanager 에 알림 여섯을 밀어 넣고 v2 API 로 되읽는다. 응답의
status.inhibitedBy 가 비어 있지 않은 것이 억제된 것이다. 수신자(슬랙)를 안 거치므로
웹훅 없이도 잰다.

    python3 monitoring/tests/inhibit_probe.py http://localhost:9093
"""
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

# db-outage-probe 가 규칙 단에서 확인한 여섯. 라벨은 alert-rules.yml 과 같아야 한다.
DB_OUTAGE_ALERTS = [
    ("PaymentSuccessRateLow", "critical", "payment"),
    ("UnknownPaymentAging", "critical", "payment"),
    ("CompensationExhausted", "critical", "payment"),
    ("OutboxConsumptionStalled", "warning", "payment"),
    ("OptimisticLockRetryRising", "warning", "payment"),
    ("ReconPendingBacklog", "warning", "settlement"),
]

# 억제돼야 하는 것. PaymentSuccessRateLow 가 같은 도메인의 warning 을 덮는다.
# settlement 는 도메인이 달라 안 덮인다 — 대사는 다른 시간대에 다른 사람이 본다.
EXPECTED_INHIBITED = {"OutboxConsumptionStalled", "OptimisticLockRetryRising"}


def post_alerts(base):
    now = datetime.now(timezone.utc)
    payload = [
        {
            "labels": {"alertname": name, "severity": sev, "domain": dom},
            "annotations": {"summary": f"{name} (억제 실측용)"},
            "startsAt": now.isoformat(),
            "endsAt": (now + timedelta(minutes=30)).isoformat(),
        }
        for name, sev, dom in DB_OUTAGE_ALERTS
    ]
    req = urllib.request.Request(
        f"{base}/api/v2/alerts",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as res:
        res.read()


def fetch_alerts(base):
    with urllib.request.urlopen(f"{base}/api/v2/alerts", timeout=10) as res:
        return json.loads(res.read())


def wait_ready(base, deadline_s=60):
    """alertmanager 가 뜰 때까지. 뜨자마자 API 가 답하지는 않는다."""
    for _ in range(deadline_s):
        try:
            with urllib.request.urlopen(f"{base}/-/ready", timeout=2) as res:
                if res.status == 200:
                    return
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(1)
    sys.exit("alertmanager 가 준비되지 않았다")


def main():
    base = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:9093").rstrip("/")
    wait_ready(base)
    post_alerts(base)

    # 억제는 즉시 반영되지 않는다. 여섯이 다 보일 때까지 기다린 뒤 한 번 더 쉰다.
    alerts = []
    for _ in range(30):
        alerts = fetch_alerts(base)
        if len(alerts) >= len(DB_OUTAGE_ALERTS):
            break
        time.sleep(1)
    time.sleep(2)
    alerts = fetch_alerts(base)

    inhibited, delivered = set(), set()
    for a in alerts:
        name = a["labels"]["alertname"]
        if a.get("status", {}).get("inhibitedBy"):
            inhibited.add(name)
        else:
            delivered.add(name)

    print(f"규칙 단에서 뜬 것   {len(alerts)}건")
    print(f"억제된 것          {len(inhibited)}건  {sorted(inhibited)}")
    print(f"사람이 받는 것     {len(delivered)}건  {sorted(delivered)}")

    problems = []
    if len(alerts) != len(DB_OUTAGE_ALERTS):
        problems.append(f"밀어 넣은 {len(DB_OUTAGE_ALERTS)}건 중 {len(alerts)}건만 보인다")
    if inhibited != EXPECTED_INHIBITED:
        problems.append(f"억제 기대 {sorted(EXPECTED_INHIBITED)} 인데 실제 {sorted(inhibited)}")
    for name in ("PaymentSuccessRateLow", "UnknownPaymentAging", "CompensationExhausted"):
        if name in inhibited:
            problems.append(f"{name} 은 critical 이라 억제되면 안 된다")
    if "ReconPendingBacklog" in inhibited:
        problems.append("ReconPendingBacklog 는 도메인이 달라 억제되면 안 된다")

    if problems:
        print("\n어긋난 것:")
        for p in problems:
            print(f"  - {p}")
        sys.exit(1)
    print(f"\nOK: 여섯이 {len(delivered)}건으로 나간다")


if __name__ == "__main__":
    main()
