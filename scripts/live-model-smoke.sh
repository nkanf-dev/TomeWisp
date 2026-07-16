#!/usr/bin/env bash
set -euo pipefail

: "${TOMEWISP_MODEL_BASE_URL:?Set TOMEWISP_MODEL_BASE_URL, including the API version path}"
: "${TOMEWISP_MODEL:?Set TOMEWISP_MODEL}"
: "${TOMEWISP_API_KEY:?Set TOMEWISP_API_KEY in the environment; never put it in a repository file}"

export TOMEWISP_LIVE_MODEL=true
export TOMEWISP_MODEL_PROTOCOL="${TOMEWISP_MODEL_PROTOCOL:-ANTHROPIC_MESSAGES}"

exec ./gradlew-curl :common:test \
  --tests dev.tomewisp.model.live.LiveModelAcceptanceTest \
  --max-workers=1
