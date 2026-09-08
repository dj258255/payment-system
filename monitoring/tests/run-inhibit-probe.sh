#!/usr/bin/env bash
# alertmanager 를 띄우고 억제를 실측한 뒤 지운다.
#
# 왜 스크립트인가: 억제는 살아 있는 alertmanager 안에서만 일어난다. promtool 은 규칙만
# 보고 amtool 은 문법만 본다. 그 사이가 비어 있어서 <여섯이 넷이 된다>가 실측이 아니라
# 설계상의 수로 남아 있었다.
#
#   ./monitoring/tests/run-inhibit-probe.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

PORT="${INHIBIT_PROBE_PORT:-19093}"
NAME="pay-inhibit-probe"

# 웹훅 URL 은 실행 시 환경변수로 들어간다. 검사용 더미로 바꿔 넣지 않으면 기동이 막힌다.
mkdir -p build/am
sed "s|'\${SLACK_WEBHOOK_URL}'|'https://hooks.slack.com/services/T0/B0/XXXX'|" \
  monitoring/alertmanager.yml > build/am/alertmanager.yml

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

docker run -d --name "$NAME" -p "$PORT:9093" \
  -v "$PWD/build/am:/etc/am" prom/alertmanager:latest \
  --config.file=/etc/am/alertmanager.yml >/dev/null

python3 monitoring/tests/inhibit_probe.py "http://localhost:$PORT"
