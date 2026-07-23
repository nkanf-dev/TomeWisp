#!/usr/bin/env bash
set -euo pipefail

mode="${1:-agent}"

case "$mode" in
  agent)
    : "${OPENALLAY_MODEL_BASE_URL:?Set OPENALLAY_MODEL_BASE_URL, including the API version path}"
    : "${OPENALLAY_MODEL:?Set OPENALLAY_MODEL}"
    : "${OPENALLAY_API_KEY:?Set OPENALLAY_API_KEY in the environment; never put it in a repository file}"
    export OPENALLAY_LIVE_MODEL=true
    export OPENALLAY_MODEL_PROTOCOL="${OPENALLAY_MODEL_PROTOCOL:-ANTHROPIC_MESSAGES}"
    test_class=dev.openallay.model.live.LiveModelAcceptanceTest
    ;;
  settings-probe)
    : "${OPENALLAY_SETTINGS_PROBE_CONFIG:?Set OPENALLAY_SETTINGS_PROBE_CONFIG to an ignored models JSON file}"
    if [[ ! -f "$OPENALLAY_SETTINGS_PROBE_CONFIG" ]]; then
      echo "Settings probe configuration does not exist" >&2
      exit 2
    fi
    export OPENALLAY_LIVE_SETTINGS_PROBE=true
    test_class=dev.openallay.model.live.LiveModelConnectionProbeAcceptanceTest
    ;;
  configured-agent)
    : "${OPENALLAY_LIVE_CONFIG_PATH:?Set OPENALLAY_LIVE_CONFIG_PATH to an ignored models.json}"
    : "${OPENALLAY_LIVE_CREDENTIAL_DB:?Set OPENALLAY_LIVE_CREDENTIAL_DB to its ignored credentials.sqlite3}"
    if [[ ! -f "$OPENALLAY_LIVE_CONFIG_PATH" || ! -f "$OPENALLAY_LIVE_CREDENTIAL_DB" ]]; then
      echo "Configured Agent profile or credential database does not exist" >&2
      exit 2
    fi
    export OPENALLAY_LIVE_CONFIGURED_AGENT=true
    test_class=dev.openallay.model.live.LiveConfiguredGameStateAcceptanceTest
    ;;
  phase-four)
    : "${OPENALLAY_LIVE_CONFIG_PATH:?Set OPENALLAY_LIVE_CONFIG_PATH to an ignored models.json}"
    : "${OPENALLAY_LIVE_CREDENTIAL_DB:?Set OPENALLAY_LIVE_CREDENTIAL_DB to its ignored credentials.sqlite3}"
    if [[ ! -f "$OPENALLAY_LIVE_CONFIG_PATH" || ! -f "$OPENALLAY_LIVE_CREDENTIAL_DB" ]]; then
      echo "Configured Agent profile or credential database does not exist" >&2
      exit 2
    fi
    export OPENALLAY_LIVE_PHASE_FOUR=true
    test_class=dev.openallay.model.live.LiveConfiguredPhaseFourAcceptanceTest
    ;;
  vfs)
    : "${OPENALLAY_MODEL_BASE_URL:?Set OPENALLAY_MODEL_BASE_URL, including the API version path}"
    : "${OPENALLAY_MODEL:?Set OPENALLAY_MODEL}"
    : "${OPENALLAY_API_KEY:?Set OPENALLAY_API_KEY in the environment; never put it in a repository file}"
    export OPENALLAY_LIVE_VFS=true
    export OPENALLAY_MODEL_PROTOCOL="${OPENALLAY_MODEL_PROTOCOL:-OPENAI_CHAT}"
    test_class=dev.openallay.model.live.LiveResourceVfsAcceptanceTest
    ;;
  *)
    echo "Usage: $0 [agent|settings-probe|configured-agent|phase-four|vfs]" >&2
    exit 2
    ;;
esac

exec ./gradlew-curl :common:test \
  --tests "$test_class" \
  --rerun-tasks \
  --max-workers=1
