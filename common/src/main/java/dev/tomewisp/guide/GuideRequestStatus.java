package dev.tomewisp.guide;

public enum GuideRequestStatus {
    PREPARING,
    CONTEXT_LOADING,
    COMPACTING,
    MODEL_WAIT,
    TOOL_WAIT,
    RATE_LIMITED,
    COMPLETING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}
