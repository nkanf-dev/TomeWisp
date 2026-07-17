package dev.tomewisp.guide;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentState;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelFailure;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class GuideStateReducer {
    private final Gson gson;

    public GuideStateReducer(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public GuideRequestSnapshot apply(
            GuideRequestSnapshot current, AgentEvent event, Instant now) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(now, "now");
        if (current.terminal()) {
            return current;
        }

        String text = current.assistantText();
        GuideRequestStatus status = current.status();
        List<GuideToolActivity> tools = current.tools();
        List<GuideSource> sources = current.sources();
        var usage = current.usage();
        Long retryAfter = current.retryAfterMillis();
        GuideFailure failure = current.failure();
        Instant terminalAt = null;

        switch (event) {
            case AgentEvent.StateChanged changed -> status = state(changed.state());
            case AgentEvent.ToolStarted started -> {
                ArrayList<GuideToolActivity> next = new ArrayList<>(tools);
                next.add(new GuideToolActivity(
                        next.size(), started.toolId(), GuideToolStatus.RUNNING, null, List.of()));
                tools = List.copyOf(next);
                status = GuideRequestStatus.TOOL_WAIT;
            }
            case AgentEvent.ToolCompleted completed -> {
                List<GuideSource> toolSources = sources(completed.toolId(), completed.normalized());
                ArrayList<GuideToolActivity> next = new ArrayList<>(tools);
                int index = lastRunning(next, completed.toolId());
                GuideToolActivity replacement = new GuideToolActivity(
                        index < 0 ? next.size() : next.get(index).index(),
                        completed.toolId(),
                        completed.failure() ? GuideToolStatus.FAILED : GuideToolStatus.SUCCEEDED,
                        completed.normalized(),
                        toolSources);
                if (index < 0) {
                    next.add(replacement);
                } else {
                    next.set(index, replacement);
                }
                tools = List.copyOf(next);
                ArrayList<GuideSource> merged = new ArrayList<>(sources);
                for (GuideSource source : toolSources) {
                    if (!merged.contains(source)) {
                        merged.add(source);
                    }
                }
                merged.sort(Comparator
                        .comparing((GuideSource value) -> value.evidence().sourceId())
                        .thenComparing(value -> value.evidence().provenance())
                        .thenComparing(GuideSource::toolId));
                sources = List.copyOf(merged);
            }
            case AgentEvent.ModelProgress progress -> {
                switch (progress.event()) {
                    case ModelEvent.TextDelta delta -> {
                        text += delta.text();
                        status = GuideRequestStatus.MODEL_WAIT;
                        retryAfter = null;
                    }
                    case ModelEvent.UsageUpdate update -> usage = update.usage();
                    case ModelEvent.RateLimited limited -> {
                        status = GuideRequestStatus.RATE_LIMITED;
                        retryAfter = limited.retryAfterMillis();
                    }
                    case ModelEvent.ReasoningDelta ignored -> {
                        return current;
                    }
                    case ModelEvent.ToolUseComplete ignored -> {}
                    case ModelEvent.MessageComplete ignored -> {}
                    case ModelFailure ignored -> {}
                }
            }
            case AgentEvent.FinalText completed -> {
                text = completed.text();
                status = GuideRequestStatus.COMPLETED;
                retryAfter = null;
                terminalAt = now;
            }
            case AgentEvent.Failed failed -> {
                failure = new GuideFailure(failed.code(), failed.message());
                status = failed.code().equals("agent_cancelled")
                        ? GuideRequestStatus.CANCELLED
                        : GuideRequestStatus.FAILED;
                retryAfter = null;
                terminalAt = now;
            }
        }
        return new GuideRequestSnapshot(
                current.requestId(),
                current.sessionId(),
                current.topology(),
                current.userMessage(),
                text,
                status,
                tools,
                sources,
                usage,
                retryAfter,
                failure,
                current.createdAt(),
                now,
                terminalAt);
    }

    private static GuideRequestStatus state(AgentState state) {
        return switch (state) {
            case IDLE, PREPARING -> GuideRequestStatus.PREPARING;
            case MODEL_WAIT -> GuideRequestStatus.MODEL_WAIT;
            case TOOL_WAIT -> GuideRequestStatus.TOOL_WAIT;
            case COMPLETED -> GuideRequestStatus.COMPLETING;
            case FAILED -> GuideRequestStatus.FAILED;
            case CANCELLED -> GuideRequestStatus.CANCELLED;
        };
    }

    private List<GuideSource> sources(String toolId, JsonObject normalized) {
        if (!normalized.has("value") || !normalized.get("value").isJsonObject()) {
            return List.of();
        }
        JsonElement evidence = normalized.getAsJsonObject("value").get("evidence");
        if (evidence == null || !evidence.isJsonArray()) {
            return List.of();
        }
        ArrayList<GuideSource> result = new ArrayList<>();
        for (JsonElement item : evidence.getAsJsonArray()) {
            result.add(new GuideSource(toolId, gson.fromJson(item, EvidenceMetadata.class)));
        }
        return List.copyOf(result);
    }

    private static int lastRunning(List<GuideToolActivity> tools, String toolId) {
        for (int index = tools.size() - 1; index >= 0; index--) {
            GuideToolActivity tool = tools.get(index);
            if (tool.status() == GuideToolStatus.RUNNING
                    && (tool.toolId().equals(toolId) || toolId.endsWith(tool.toolId()))) {
                return index;
            }
        }
        return -1;
    }
}
