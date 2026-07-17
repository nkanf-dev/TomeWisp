package dev.tomewisp.guide;

public enum GuideRequestStatus {
    PREPARING,
    MODEL_WAIT,
    TOOL_WAIT,
    RATE_LIMITED,
    COMPLETING,
    COMPLETED,
    FAILED,
    CANCELLED
}
