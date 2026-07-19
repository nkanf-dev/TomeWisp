package dev.tomewisp.guide;

/** Closed, redacted vocabulary for player-observable request progress. */
public enum GuideRequestPhase {
    PREPARING,
    CONTEXT_LOADING,
    COMPACTING,
    ENDPOINT_WAIT,
    MODEL_WAIT,
    RESPONSE_STREAMING,
    TOOL_WAIT,
    COMPLETING
}
