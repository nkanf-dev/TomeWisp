package dev.tomewisp.bridge.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelFailure;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Strict common wire codec for request-correlated server Agent events. */
public final class ServerAgentEventCodec {
    private final Gson gson;

    public ServerAgentEventCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public ServerAgentEventPayload encode(UUID requestId, AgentEvent event) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(event, "event");
        String type = type(event);
        boolean terminal = event instanceof AgentEvent.FinalText || event instanceof AgentEvent.Failed;
        Object body = event instanceof AgentEvent.ModelProgress progress ? progress.event() : event;
        return new ServerAgentEventPayload(
                BridgeProtocol.VERSION, requestId, type, gson.toJson(body), terminal);
    }

    public AgentEvent decode(ServerAgentEventPayload payload, UUID expectedRequestId) {
        Objects.requireNonNull(payload, "payload");
        if (!payload.requestId().equals(expectedRequestId)) {
            throw new IllegalArgumentException("Server Agent event request ID mismatch");
        }
        JsonObject body;
        try {
            var parsed = JsonParser.parseString(payload.eventJson());
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Server Agent event body must be an object");
            }
            body = parsed.getAsJsonObject();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Malformed server Agent event JSON", failure);
        }

        AgentEvent event = switch (payload.eventType()) {
            case "state" -> new AgentEvent.StateChanged(read(body, Set.of("state"), AgentEvent.StateChanged.class).state());
            case "text_delta" -> new AgentEvent.ModelProgress(
                    read(body, Set.of("text"), ModelEvent.TextDelta.class));
            case "reasoning_delta" -> new AgentEvent.ModelProgress(
                    read(body, Set.of("text"), ModelEvent.ReasoningDelta.class));
            case "tool_use_complete" -> new AgentEvent.ModelProgress(
                    read(body, Set.of("id", "name", "input"), ModelEvent.ToolUseComplete.class));
            case "usage" -> new AgentEvent.ModelProgress(
                    read(body, Set.of("usage"), ModelEvent.UsageUpdate.class));
            case "rate_limited" -> new AgentEvent.ModelProgress(
                    read(body, Set.of("retryAfterMillis", "attempt"), ModelEvent.RateLimited.class));
            case "model_complete" -> new AgentEvent.ModelProgress(
                    read(body, Set.of("stopReason"), ModelEvent.MessageComplete.class));
            case "model_failure" -> new AgentEvent.ModelProgress(readModelFailure(body));
            case "tool_started" -> read(body, Set.of("toolId"), AgentEvent.ToolStarted.class);
            case "tool_completed" -> read(
                    body,
                    Set.of("toolId", "failure", "normalized"),
                    AgentEvent.ToolCompleted.class);
            case "final_text" -> read(body, Set.of("text"), AgentEvent.FinalText.class);
            case "failed" -> read(body, Set.of("code", "message"), AgentEvent.Failed.class);
            default -> throw new IllegalArgumentException(
                    "Unknown server Agent event type " + payload.eventType());
        };
        boolean expectedTerminal = event instanceof AgentEvent.FinalText || event instanceof AgentEvent.Failed;
        if (payload.terminal() != expectedTerminal) {
            throw new IllegalArgumentException("Server Agent event terminal flag is inconsistent");
        }
        return event;
    }

    private <T> T read(JsonObject body, Set<String> fields, Class<T> type) {
        if (!body.keySet().equals(fields)) {
            throw new IllegalArgumentException(
                    "Server Agent event schema mismatch for " + type.getSimpleName());
        }
        T value = gson.fromJson(body, type);
        if (value == null) {
            throw new IllegalArgumentException("Server Agent event decoded to null");
        }
        return value;
    }

    private ModelFailure readModelFailure(JsonObject body) {
        Set<String> fields = body.keySet();
        if (!fields.equals(Set.of("code", "message"))
                && !fields.equals(Set.of("code", "message", "httpStatus"))) {
            throw new IllegalArgumentException("Server Agent event schema mismatch for ModelFailure");
        }
        return gson.fromJson(body, ModelFailure.class);
    }

    private static String type(AgentEvent event) {
        return switch (event) {
            case AgentEvent.StateChanged ignored -> "state";
            case AgentEvent.ToolStarted ignored -> "tool_started";
            case AgentEvent.ToolCompleted ignored -> "tool_completed";
            case AgentEvent.FinalText ignored -> "final_text";
            case AgentEvent.Failed ignored -> "failed";
            case AgentEvent.ModelProgress progress -> switch (progress.event()) {
                case ModelEvent.TextDelta ignored -> "text_delta";
                case ModelEvent.ReasoningDelta ignored -> "reasoning_delta";
                case ModelEvent.ToolUseComplete ignored -> "tool_use_complete";
                case ModelEvent.UsageUpdate ignored -> "usage";
                case ModelEvent.RateLimited ignored -> "rate_limited";
                case ModelEvent.MessageComplete ignored -> "model_complete";
                case ModelFailure ignored -> "model_failure";
            };
        };
    }
}
