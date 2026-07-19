package dev.openallay.agent.trace;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.openallay.agent.AgentRequest;
import dev.openallay.agent.AgentState;
import dev.openallay.agent.tool.AgentToolResult;
import dev.openallay.model.ModelTurn;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class LiveAgentTraceRecorder {
    private final Gson gson;
    private final AgentRequest request;
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final List<LiveTraceEvent> events = new ArrayList<>();

    public LiveAgentTraceRecorder(Gson gson, AgentRequest request) {
        this.gson = gson;
        this.request = request;
        JsonObject initial = new JsonObject();
        initial.addProperty("userMessage", request.userMessage());
        add("request", initial);
    }

    public synchronized void state(AgentState state) {
        JsonObject payload = new JsonObject();
        payload.addProperty("state", state.name());
        add("state", payload);
    }

    public synchronized void modelTurn(ModelTurn turn) {
        add("model_turn", gson.toJsonTree(turn));
    }

    public synchronized void toolCall(String toolId, JsonObject arguments) {
        JsonObject payload = new JsonObject();
        payload.addProperty("toolId", toolId);
        payload.add("arguments", arguments.deepCopy());
        add("tool_call", payload);
    }

    public synchronized void toolResult(AgentToolResult result) {
        JsonObject payload = new JsonObject();
        payload.addProperty("toolId", result.toolId());
        payload.addProperty("failure", result.failure());
        payload.add("result", result.normalized());
        add("tool_result", payload);
    }

    public synchronized void failure(String code, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.addProperty("message", message);
        add("failure", payload);
    }

    public synchronized LiveAgentTrace finish(
            AgentState state, String finalText, String errorCode) {
        return new LiveAgentTrace(
                1,
                request.requestId(),
                request.actorId(),
                request.sessionId(),
                startedAt,
                Instant.now(),
                state,
                List.copyOf(events),
                finalText,
                errorCode);
    }

    private void add(String type, com.google.gson.JsonElement payload) {
        events.add(new LiveTraceEvent(type, System.nanoTime() - startedNanos, payload));
    }
}
