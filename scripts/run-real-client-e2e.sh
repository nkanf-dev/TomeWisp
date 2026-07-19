#!/usr/bin/env bash
set -euo pipefail

loader="${1:-}"
if [[ "$loader" != "fabric" && "$loader" != "neoforge" ]]; then
  echo "usage: $0 fabric|neoforge" >&2
  exit 2
fi

fixture_port="${TOMEWISP_E2E_FIXTURE_PORT:-18765}"
model_mode="${TOMEWISP_E2E_MODEL_MODE:-client}"
if [[ "$model_mode" != "client" && "$model_mode" != "server" ]]; then
  echo "TOMEWISP_E2E_MODEL_MODE must be client or server" >&2
  exit 2
fi
run_dir="$loader/runs/client"
report="${TOMEWISP_E2E_REPORT:-$PWD/build/e2e/$loader-real-client.json}"
mkdir -p "$run_dir/config/tomewisp" "$(dirname "$report")"
model_config="$run_dir/config/tomewisp/models.json"
server_model_config="$run_dir/config/tomewisp/server-model.json"
config_backup_dir="$(mktemp -d)"
had_model_config=false
had_server_model_config=false
if [[ -f "$model_config" ]]; then
  cp "$model_config" "$config_backup_dir/models.json"
  had_model_config=true
fi
if [[ -f "$server_model_config" ]]; then
  cp "$server_model_config" "$config_backup_dir/server-model.json"
  had_server_model_config=true
fi
for credential_file in credentials.sqlite3 credentials.sqlite3-wal credentials.sqlite3-shm; do
  if [[ -f "$run_dir/config/tomewisp/$credential_file" ]]; then
    cp "$run_dir/config/tomewisp/$credential_file" "$config_backup_dir/$credential_file"
  fi
done
fixture_pid=""
cleanup() {
  if [[ -n "$fixture_pid" ]]; then
    kill "$fixture_pid" 2>/dev/null || true
  fi
  if [[ "$had_model_config" == true ]]; then
    cp "$config_backup_dir/models.json" "$model_config"
  else
    rm -f "$model_config"
  fi
  if [[ "$had_server_model_config" == true ]]; then
    cp "$config_backup_dir/server-model.json" "$server_model_config"
  else
    rm -f "$server_model_config"
  fi
  # The temporary E2E profile intentionally references no local secrets. Startup cleanup may
  # therefore collect the ordinary development profile's rows, so restore the whole SQLite
  # database triplet after the graphical client has closed.
  for credential_file in credentials.sqlite3 credentials.sqlite3-wal credentials.sqlite3-shm; do
    rm -f "$run_dir/config/tomewisp/$credential_file"
    if [[ -f "$config_backup_dir/$credential_file" ]]; then
      cp "$config_backup_dir/$credential_file" "$run_dir/config/tomewisp/$credential_file"
    fi
  done
  rm -rf "$config_backup_dir"
}
trap cleanup EXIT INT TERM

python3 - "$model_config" "$fixture_port" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
path.write_text(json.dumps({
    "schemaVersion": 1,
    "defaultProfileId": "e2e-fixture",
    "profiles": [{
        "id": "e2e-fixture",
        "displayName": "TomeWisp E2E Fixture",
        "enabled": True,
        "protocol": "openai_chat",
        "baseUrl": f"http://127.0.0.1:{sys.argv[2]}/v1/",
        "model": "tomewisp-e2e-fixture",
        "apiKeyEnv": "TOMEWISP_E2E_FIXTURE_KEY",
        "contextWindowTokens": 256000,
        "maxOutputTokens": 8192,
        "connectTimeoutSeconds": 10,
        "requestTimeoutSeconds": 120,
    }],
}), encoding="utf-8")
PY
if [[ "$model_mode" == "server" ]]; then
  python3 - "$server_model_config" "$fixture_port" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
path.write_text(json.dumps({
    "enabled": True,
    "protocol": "openai_chat",
    "baseUrl": f"http://127.0.0.1:{sys.argv[2]}/v1/",
    "model": "tomewisp-e2e-server-fixture",
    "apiKeyEnv": "TOMEWISP_E2E_FIXTURE_KEY",
    "contextWindowTokens": 256000,
    "maxOutputTokens": 8192,
    "connectTimeoutSeconds": 10,
    "requestTimeoutSeconds": 120,
}), encoding="utf-8")
PY
fi
export TOMEWISP_E2E_FIXTURE_KEY="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"

python3 scripts/e2e-model-fixture.py --port "$fixture_port" &
fixture_pid=$!

echo "The harness is opt-in and will open a graphical Minecraft client."
echo "Connect it to a test world/server; the probe starts after a player exists."
client_args=()
if [[ -n "${TOMEWISP_E2E_QUICK_PLAY_WORLD:-}" ]]; then
  if [[ "$loader" == "fabric" ]]; then
    client_args=("--args=--quickPlaySingleplayer \"$TOMEWISP_E2E_QUICK_PLAY_WORLD\"")
  else
    client_args=("-Dtomewisp.e2e.quickPlayWorld=$TOMEWISP_E2E_QUICK_PLAY_WORLD")
  fi
fi
./gradlew-curl ":$loader:runClient" --max-workers=1 \
  "${client_args[@]}" \
  -Dtomewisp.e2e.enabled=true \
  -Dtomewisp.e2e.question="${TOMEWISP_E2E_QUESTION:-请查询铁块的配方，精确读取后检查库存并计算是否可制作，最后列出当前知识来源。}" \
  -Dtomewisp.e2e.report="$report" \
  -Dtomewisp.e2e.scenario="${TOMEWISP_E2E_SCENARIO:-phase-4-semantic-history}" \
  -Dtomewisp.e2e.modelMode="$model_mode" \
  -Dtomewisp.e2e.historySeedRequests="${TOMEWISP_E2E_HISTORY_SEED_REQUESTS:-0}" \
  -Dtomewisp.e2e.screenshotRoot="${TOMEWISP_E2E_SCREENSHOT_ROOT:-}" \
  -Dtomewisp.e2e.shutdownAfterScreenshots="${TOMEWISP_E2E_SHUTDOWN_AFTER_SCREENSHOTS:-false}" \
  -Dtomewisp.e2e.shutdown="${TOMEWISP_E2E_SHUTDOWN:-true}"

test -s "$report"
python3 - "$report" <<'PY'
import json, pathlib, sys
report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if report.get("outcome") != "COMPLETED":
    raise SystemExit("E2E did not complete: " + json.dumps({
        "outcome": report.get("outcome"),
        "failureCode": report.get("failureCode"),
        "failureMessage": report.get("failureMessage"),
    }, ensure_ascii=False))
metrics = report.get("semanticMetrics", {})
scenario = report.get("scenario")
if scenario == "phase-4-game-state":
    tool_ids = report.get("toolIds", [])
    expected_sections = [
        "OVERVIEW", "MODS", "OPTIONS", "PACKS", "SHADERS",
        "DIAGNOSTICS", "PLAYER", "WORLD_QUERY",
    ]
    probes = report.get("toolProbes", [])
    if tool_ids != ["tomewisp:inspect_game_state"] * len(expected_sections):
        raise SystemExit("E2E did not inspect every registered outer game-state section")
    if len(probes) != len(expected_sections):
        raise SystemExit("E2E game-state probe count is incomplete")
    for expected, probe in zip(expected_sections, probes):
        if (probe.get("toolId") != "tomewisp:inspect_game_state"
                or probe.get("status") != "SUCCEEDED"
                or probe.get("section") != expected
                or probe.get("failureCode") is not None):
            raise SystemExit("E2E game-state section did not complete successfully: "
                             + repr({"expected": expected, "probe": probe}))
    if metrics.get("assistantSegments", 0) < 9:
        raise SystemExit("E2E did not preserve game-state tool chronology")
    raise SystemExit(0)
if scenario == "phase-4-server-client-tools":
    if report.get("topology") != "SERVER":
        raise SystemExit("E2E did not use the server-hosted model topology")
    probes = report.get("toolProbes", [])
    expected_sections = [
        "OVERVIEW", "MODS", "OPTIONS", "PACKS", "SHADERS",
        "DIAGNOSTICS", "PLAYER", "WORLD_QUERY",
    ]
    if report.get("toolIds", []) != ["tomewisp:inspect_game_state"] * 8:
        raise SystemExit("E2E did not route the complete game-state Tool sequence")
    for expected, probe in zip(expected_sections[:7], probes[:7]):
        if (probe.get("section") != expected or probe.get("status") != "SUCCEEDED"):
            raise SystemExit("E2E client Tool section failed: " + repr(probe))
    if len(probes) != 8 or not (
            probes[-1].get("status") == "SUCCEEDED"
            or (probes[-1].get("status") == "FAILED"
                and probes[-1].get("failureCode") == "permission_denied")):
        raise SystemExit("E2E server-owned world query did not preserve authority")
    if metrics.get("assistantSegments", 0) < 9:
        raise SystemExit("E2E did not continue after the complete Tool chronology")
    raise SystemExit(0)
if metrics.get("assistantSegments", 0) < 6:
    raise SystemExit("E2E did not preserve assistant/tool chronology")
required_components = {
    "item_row", "recipe_grid", "ingredient_check", "craftability_summary",
    "progress_steps", "source_summary", "status_badge", "choice_group",
}
observed_components = set(report.get("controlledComponentTypes", []))
if not required_components.issubset(observed_components):
    raise SystemExit("E2E did not retain the complete controlled component catalog: "
                     + repr(sorted(required_components - observed_components)))
if metrics.get("controlledComponents", 0) < len(required_components):
    raise SystemExit("E2E controlled component count is incomplete")
if metrics.get("semanticFallbacks", 0) < 1:
    raise SystemExit("E2E did not retain malformed-component fallback")
if "semantic_component_unsupported" not in report.get("semanticDiagnosticCodes", []):
    raise SystemExit("E2E missing redacted fallback diagnostic")
if "tomewisp:list_knowledge_sources" not in report.get("toolIds", []):
    raise SystemExit("E2E missing knowledge-source tool")
minimum_history = int(__import__("os").environ.get(
    "TOMEWISP_E2E_MIN_HISTORY_REQUESTS", "1"))
history = report.get("historyMetrics", {})
if history.get("totalRequests", 0) < minimum_history:
    raise SystemExit("E2E history total is below the required minimum")
if __import__("os").environ.get("TOMEWISP_E2E_REQUIRE_PAGED_HISTORY") == "true":
    if history.get("loadedRequests", 0) >= history.get("totalRequests", 0):
        raise SystemExit("E2E did not restore a windowed history projection")
    if history.get("hasEarlier") != 1:
        raise SystemExit("E2E history window does not expose earlier requests")
PY
echo "E2E report: $report"
