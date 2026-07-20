package dev.openallay.agent.tool;

/** Internal projection supplied by Tools that publish exact truth before returning. */
public interface AgentToolProjectionCarrier {
    ModelToolResultView modelView();

    ToolUiReference uiReference();

    ToolResultDiagnostics diagnostics();

    default boolean failure() {
        return false;
    }
}
