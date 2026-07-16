package dev.tomewisp.agent;

public enum AgentState {
    IDLE,
    PREPARING,
    MODEL_WAIT,
    TOOL_WAIT,
    COMPLETED,
    FAILED,
    CANCELLED
}
