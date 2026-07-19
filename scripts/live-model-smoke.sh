#!/usr/bin/env bash
set -euo pipefail

mode="${1:-agent}"

case "$mode" in
  agent)
    : "${TOMEWISP_MODEL_BASE_URL:?Set TOMEWISP_MODEL_BASE_URL, including the API version path}"
    : "${TOMEWISP_MODEL:?Set TOMEWISP_MODEL}"
    : "${TOMEWISP_API_KEY:?Set TOMEWISP_API_KEY in the environment; never put it in a repository file}"
    export TOMEWISP_LIVE_MODEL=true
    export TOMEWISP_MODEL_PROTOCOL="${TOMEWISP_MODEL_PROTOCOL:-ANTHROPIC_MESSAGES}"
    test_class=dev.tomewisp.model.live.LiveModelAcceptanceTest
    ;;
  settings-probe)
    : "${TOMEWISP_SETTINGS_PROBE_CONFIG:?Set TOMEWISP_SETTINGS_PROBE_CONFIG to an ignored models JSON file}"
    if [[ ! -f "$TOMEWISP_SETTINGS_PROBE_CONFIG" ]]; then
      echo "Settings probe configuration does not exist" >&2
      exit 2
    fi
    export TOMEWISP_LIVE_SETTINGS_PROBE=true
    test_class=dev.tomewisp.model.live.LiveModelConnectionProbeAcceptanceTest
    ;;
  configured-agent)
    : "${TOMEWISP_LIVE_CONFIG_PATH:?Set TOMEWISP_LIVE_CONFIG_PATH to an ignored models.json}"
    : "${TOMEWISP_LIVE_CREDENTIAL_DB:?Set TOMEWISP_LIVE_CREDENTIAL_DB to its ignored credentials.sqlite3}"
    if [[ ! -f "$TOMEWISP_LIVE_CONFIG_PATH" || ! -f "$TOMEWISP_LIVE_CREDENTIAL_DB" ]]; then
      echo "Configured Agent profile or credential database does not exist" >&2
      exit 2
    fi
    export TOMEWISP_LIVE_CONFIGURED_AGENT=true
    test_class=dev.tomewisp.model.live.LiveConfiguredGameStateAcceptanceTest
    ;;
  *)
    echo "Usage: $0 [agent|settings-probe|configured-agent]" >&2
    exit 2
    ;;
esac

exec ./gradlew-curl :common:test \
  --tests "$test_class" \
  --max-workers=1
