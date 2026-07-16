package dev.tomewisp.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.tomewisp.agent.session.AgentSessionStore;
import dev.tomewisp.agent.tool.AgentToolExecutor;
import dev.tomewisp.agent.tool.AgentToolResult;
import dev.tomewisp.agent.trace.LiveAgentTrace;
import dev.tomewisp.agent.trace.LiveAgentTraceRecorder;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.ModelClientException;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.model.ModelRequest;
import dev.tomewisp.model.ModelRole;
import dev.tomewisp.model.ModelTurn;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.trace.replay.ToolResultNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public final class GameGuideAgent {
    private final ModelClient model;
    private final AgentToolExecutor tools;
    private final AgentSessionStore sessions;
    private final Gson gson;
    private final ToolResultNormalizer canonicalizer;

    public GameGuideAgent(
            ModelClient model,
            AgentToolExecutor tools,
            AgentSessionStore sessions,
            Gson gson) {
        this.model = Objects.requireNonNull(model, "model");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.gson = Objects.requireNonNull(gson, "gson");
        canonicalizer = new ToolResultNormalizer(gson);
    }

    public CompletableFuture<AgentResult> ask(
            AgentRequest request, Consumer<AgentEvent> events) {
        ToolResult<AgentSessionStore.Lease> reservation =
                sessions.reserve(request.sessionKey(), request.requestId());
        if (reservation instanceof ToolResult.Failure<AgentSessionStore.Lease> failure) {
            events.accept(new AgentEvent.Failed(failure.code(), failure.message()));
            return CompletableFuture.completedFuture(new AgentResult(
                    AgentState.FAILED, null, failure.code(), failure.message(), null));
        }
        AgentSessionStore.Lease lease =
                ((ToolResult.Success<AgentSessionStore.Lease>) reservation).value();
        LiveAgentTraceRecorder trace = new LiveAgentTraceRecorder(gson, request);
        transition(AgentState.PREPARING, trace, events);
        List<ModelMessage> messages = new ArrayList<>(lease.history());
        messages.add(ModelMessage.userText(request.userMessage()));
        transition(AgentState.MODEL_WAIT, trace, events);
        return loop(request, lease, messages, null, trace, events)
                .exceptionally(throwable -> fail(request, lease, trace, events, throwable));
    }

    private CompletableFuture<AgentResult> loop(
            AgentRequest request,
            AgentSessionStore.Lease lease,
            List<ModelMessage> messages,
            String previousCallKey,
            LiveAgentTraceRecorder trace,
            Consumer<AgentEvent> events) {
        lease.cancellation().throwIfCancelled();
        ModelRequest modelRequest = new ModelRequest(
                request.systemPrompt(),
                messages,
                tools.definitions(),
                request.stream(),
                request.sessionKey().schedulingKey());
        return model.complete(
                        modelRequest,
                        event -> {
                            if (!lease.cancellation().isCancelled()) {
                                events.accept(new AgentEvent.ModelProgress(event));
                            }
                        },
                        lease.cancellation())
                .thenCompose(turn -> {
                    lease.cancellation().throwIfCancelled();
                    trace.modelTurn(turn);
                    List<ModelMessage> nextMessages = new ArrayList<>(messages);
                    nextMessages.add(new ModelMessage(ModelRole.ASSISTANT, turn.content()));
                    if (turn.toolUses().isEmpty()) {
                        if (turn.text().isBlank()) {
                            throw new ModelClientException(new dev.tomewisp.model.ModelFailure(
                                    "model_protocol_error",
                                    "Model returned neither tool use nor final text",
                                    null));
                        }
                        transition(AgentState.COMPLETED, trace, events);
                        sessions.finish(lease, nextMessages);
                        LiveAgentTrace completed = trace.finish(AgentState.COMPLETED, turn.text(), null);
                        events.accept(new AgentEvent.FinalText(turn.text()));
                        return CompletableFuture.completedFuture(new AgentResult(
                                AgentState.COMPLETED, turn.text(), null, null, completed));
                    }
                    transition(AgentState.TOOL_WAIT, trace, events);
                    return executeTools(
                                    request,
                                    lease,
                                    turn.toolUses(),
                                    nextMessages,
                                    previousCallKey,
                                    trace,
                                    events)
                            .thenCompose(outcome -> {
                                transition(AgentState.MODEL_WAIT, trace, events);
                                return loop(
                                        request,
                                        lease,
                                        outcome.messages(),
                                        outcome.lastCallKey(),
                                        trace,
                                        events);
                            });
                });
    }

    private CompletableFuture<ToolOutcome> executeTools(
            AgentRequest request,
            AgentSessionStore.Lease lease,
            List<ModelContent.ToolUse> calls,
            List<ModelMessage> messages,
            String previousCallKey,
            LiveAgentTraceRecorder trace,
            Consumer<AgentEvent> events) {
        CompletableFuture<ToolOutcome> chain = CompletableFuture.completedFuture(
                new ToolOutcome(new ArrayList<>(messages), previousCallKey, new ArrayList<>()));
        for (ModelContent.ToolUse call : calls) {
            chain = chain.thenCompose(outcome -> {
                lease.cancellation().throwIfCancelled();
                String callKey = call.name() + ":" + canonical(call.input());
                if (callKey.equals(outcome.lastCallKey())) {
                    throw new ModelClientException(new dev.tomewisp.model.ModelFailure(
                            "repeated_tool_call",
                            "Model repeated the same tool call without new information",
                            null));
                }
                String exposedId = call.name();
                events.accept(new AgentEvent.ToolStarted(exposedId));
                trace.toolCall(exposedId, call.input());
                return tools.execute(call.name(), call.input(), request.context(), lease.cancellation())
                        .thenApply(result -> {
                            trace.toolResult(result);
                            events.accept(new AgentEvent.ToolCompleted(result.toolId(), result.failure()));
                            List<ModelContent> results = new ArrayList<>(outcome.results());
                            results.add(new ModelContent.ToolResult(
                                    call.id(), result.normalized(), result.failure()));
                            return new ToolOutcome(outcome.messages(), callKey, results);
                        });
            });
        }
        return chain.thenApply(outcome -> {
            List<ModelMessage> updated = new ArrayList<>(outcome.messages());
            updated.add(new ModelMessage(ModelRole.USER, outcome.results()));
            return new ToolOutcome(updated, outcome.lastCallKey(), List.of());
        });
    }

    private AgentResult fail(
            AgentRequest request,
            AgentSessionStore.Lease lease,
            LiveAgentTraceRecorder trace,
            Consumer<AgentEvent> events,
            Throwable throwable) {
        Throwable cause = unwrap(throwable);
        String code;
        String message;
        AgentState state;
        if (lease.cancellation().isCancelled()) {
            code = "agent_cancelled";
            message = "Agent request was cancelled";
            state = AgentState.CANCELLED;
        } else if (cause instanceof ModelClientException exception) {
            code = exception.failure().code();
            message = exception.failure().message();
            state = code.equals("agent_cancelled") ? AgentState.CANCELLED : AgentState.FAILED;
        } else {
            code = "agent_failure";
            message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            state = AgentState.FAILED;
        }
        trace.failure(code, message);
        transition(state, trace, events);
        sessions.finish(lease, lease.history());
        LiveAgentTrace completed = trace.finish(state, null, code);
        events.accept(new AgentEvent.Failed(code, message));
        return new AgentResult(state, null, code, message, completed);
    }

    private String canonical(JsonObject value) {
        return gson.toJson(canonicalizer.canonicalize(value));
    }

    private static void transition(
            AgentState state,
            LiveAgentTraceRecorder trace,
            Consumer<AgentEvent> events) {
        trace.state(state);
        events.accept(new AgentEvent.StateChanged(state));
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record ToolOutcome(
            List<ModelMessage> messages,
            String lastCallKey,
            List<ModelContent> results) {}
}
