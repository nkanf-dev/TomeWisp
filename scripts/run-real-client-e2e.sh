#!/usr/bin/env bash
set -euo pipefail

loader="${1:-}"
if [[ "$loader" != "fabric" && "$loader" != "neoforge" ]]; then
  echo "usage: $0 fabric|neoforge" >&2
  exit 2
fi

fixture_port="${TOMEWISP_E2E_FIXTURE_PORT:-18765}"
run_dir="$loader/runs/client"
report="${TOMEWISP_E2E_REPORT:-$PWD/build/e2e/$loader-real-client.json}"
mkdir -p "$run_dir/config/tomewisp" "$(dirname "$report")"
model_config="$run_dir/config/tomewisp/model.json"
python3 - "$model_config" "$fixture_port" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
path.write_text(json.dumps({
    "enabled": True,
    "protocol": "openai_chat",
    "baseUrl": f"http://127.0.0.1:{sys.argv[2]}/v1/",
    "model": "tomewisp-e2e-fixture",
    "apiKey": "loopback-fixture-not-a-secret",
}), encoding="utf-8")
PY

python3 scripts/e2e-model-fixture.py --port "$fixture_port" &
fixture_pid=$!
cleanup() {
  kill "$fixture_pid" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "The harness is opt-in and will open a graphical Minecraft client."
echo "Connect it to a test world/server; the probe starts after a player exists."
./gradlew-curl ":$loader:runClient" --max-workers=1 \
  -Dtomewisp.e2e.enabled=true \
  -Dtomewisp.e2e.question="我能制作一个铁块吗？请查询配方和库存后计算。" \
  -Dtomewisp.e2e.report="$report" \
  -Dtomewisp.e2e.scenario="real-client-grounded-craftability" \
  -Dtomewisp.e2e.modelMode=client \
  -Dtomewisp.e2e.shutdown=true

test -s "$report"
echo "E2E report: $report"
