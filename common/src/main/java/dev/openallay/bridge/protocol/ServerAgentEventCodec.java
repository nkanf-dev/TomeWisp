package dev.openallay.bridge.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.agent.AgentEvent;
import dev.openallay.agent.context.ContextCheckpointCodec;
import dev.openallay.guide.GuideToolMessageCodec;
import dev.openallay.model.ModelEvent;
import dev.openallay.model.ModelFailure;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Strict common wire codec for request-correlated server Agent events. */
public final class ServerAgentEventCodec {
    private final Gson gson;
    private final ContextCheckpointCodec checkpoints = new ContextCheckpointCodec();

    public ServerAgentEventCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public ServerAgentEventPayload encode(UUID requestId, AgentEvent event) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(event, "event");
        String type = type(event);
        boolean terminal = event instanceof AgentEvent.FinalText || event instanceof AgentEvent.Failed;
        String eventJson;
        if (event instanceof AgentEvent.ContextCompacted compacted) {
            eventJson = checkpoints.encode(compacted.checkpoint());
        } else if (event instanceof AgentEvent.ToolStarted started) {
            eventJson = encodeToolStarted(started).toString();
        } else {
            Object body = event instanceof AgentEvent.ModelProgress progress ? progress.event() : event;
            eventJson = gson.toJson(body);
        }
        return new ServerAgentEventPayload(
                BridgeProtocol.VERSION, requestId, type, eventJson, terminal);
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
            case "context_compacted" ->
                    new AgentEvent.ContextCompacted(checkpoints.decode(body.toString()));
            case "text_delta" -> new AgentEvent.ModelProgress(
                    read(body, Set.of("text"), ModelEvent.TextDelta.class));
            case "reasoning_delta" -> new AgentEvent.ModelProgress(
                    read(body, Set.of("text"), ModelEvent.ReasoningDelta.class));
            case "tool_use_complete" -> new AgentEvent.ModelProgress(
                    read(body, Set.of("id", "name", "input"), ModelEvent.ToolUseComplete.class));
            case "usage" -> new AgentEvent.ModelProgress(
                    read(body, Set.of("usage"), ModelEvent.UsageUpdate.class));
            case "model_attempt_started" -> new AgentEvent.ModelProgress(
                    readAttemptStarted(body));
            case "model_response_started" -> new AgentEvent.ModelProgress(
                    read(body, Set.of(), ModelEvent.ResponseStarted.class));
            case "rate_limited" -> new AgentEvent.ModelProgress(
                    read(body, Set.of("retryAfterMillis", "attempt"), ModelEvent.RateLimited.class));
            case "model_complete" -> new AgentEvent.ModelProgress(
                    read(body, Set.of("stopReason"), ModelEvent.MessageComplete.class));
            case "model_failure" -> new AgentEvent.ModelProgress(readModelFailure(body));
            case "tool_started" -> readToolStarted(body);
            case "tool_completed" -> read(
                    body,
                    Set.of("invocationId", "toolId", "failure", "normalized"),
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

    private ModelEvent.AttemptStarted readAttemptStarted(JsonObject body) {
        if (!body.keySet().equals(Set.of("attempt"))
                && !body.keySet().equals(Set.of("attempt", "attemptTimeoutMillis"))) {
            throw new IllegalArgumentException(
                    "Server Agent event schema mismatch for AttemptStarted");
        }
        return gson.fromJson(body, ModelEvent.AttemptStarted.class);
    }

    private static JsonObject encodeToolStarted(AgentEvent.ToolStarted started) {
        JsonObject body = new JsonObject();
        body.addProperty("invocationId", started.invocationId());
        body.addProperty("toolId", started.toolId());
        body.add("presentationMessages", GuideToolMessageCodec.encode(
                started.presentationMessages()));
        return body;
    }

    private static AgentEvent.ToolStarted readToolStarted(JsonObject body) {
        if (!body.keySet().equals(Set.of(
                "invocationId", "toolId", "presentationMessages"))) {
            throw new IllegalArgumentException("Server Tool start schema mismatch");
        }
        if (!body.get("invocationId").isJsonPrimitive()
                || !body.getAsJsonPrimitive("invocationId").isString()
                || !body.get("toolId").isJsonPrimitive()
                || !body.getAsJsonPrimitive("toolId").isString()) {
            throw new IllegalArgumentException("Server Tool start identity types are invalid");
        }
        return new AgentEvent.ToolStarted(
                body.get("invocationId").getAsString(),
                body.get("toolId").getAsString(),
                GuideToolMessageCodec.decode(body.get("presentationMessages")));
    }

    private static String type(AgentEvent event) {
        return switch (event) {
            case AgentEvent.StateChanged ignored -> "state";
            case AgentEvent.ContextCompacted ignored -> "context_compacted";
            case AgentEvent.ToolStarted ignored -> "tool_started";
            case AgentEvent.ToolCompleted ignored -> "tool_completed";
            case AgentEvent.FinalText ignored -> "final_text";
            case AgentEvent.Failed ignored -> "failed";
            case AgentEvent.ModelProgress progress -> switch (progress.event()) {
                case ModelEvent.TextDelta ignored -> "text_delta";
                case ModelEvent.ReasoningDelta ignored -> "reasoning_delta";
                case ModelEvent.ToolUseComplete ignored -> "tool_use_complete";
                case ModelEvent.UsageUpdate ignored -> "usage";
                case ModelEvent.AttemptStarted ignored -> "model_attempt_started";
                case ModelEvent.ResponseStarted ignored -> "model_response_started";
                case ModelEvent.RateLimited ignored -> "rate_limited";
                case ModelEvent.MessageComplete ignored -> "model_complete";
                case ModelFailure ignored -> "model_failure";
            };
        };
    }
}
