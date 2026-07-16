package dev.tomewisp.agent.trace;

import com.google.gson.JsonElement;

public record LiveTraceEvent(String type, long elapsedNanos, JsonElement payload) {
    public LiveTraceEvent {
        if (type == null || type.isBlank() || elapsedNanos < 0) {
            throw new IllegalArgumentException("Trace event type and non-negative time are required");
        }
        payload = payload == null ? null : payload.deepCopy();
    }

    @Override
    public JsonElement payload() {
        return payload == null ? null : payload.deepCopy();
    }
}
