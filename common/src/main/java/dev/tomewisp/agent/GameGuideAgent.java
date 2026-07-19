package dev.tomewisp.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.tomewisp.agent.session.AgentSessionStore;
import dev.tomewisp.agent.context.ContextCompactor;
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
import dev.tomewisp.guide.GuideToolInvocationPresentation;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.trace.replay.ToolResultNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public final class GameGuideAgent {
    private static final String REPEATED_CALL_SENTINEL = "\u0000no_new_information";
    private final ModelClient model;
    private final AgentToolExecutor tools;
    private final AgentSessionStore sessions;
    private final Gson gson;
    private final ToolResultNormalizer canonicalizer;
    private final ContextCompactor compactor;

    public GameGuideAgent(
            ModelClient model,
            AgentToolExecutor tools,
            AgentSessionStore sessions,
            Gson gson) {
        this(model, tools, sessions, gson, null);
    }

    public GameGuideAgent(
            ModelClient model,
            AgentToolExecutor tools,
            AgentSessionStore sessions,
            Gson gson,
            ContextCompactor compactor) {
        this.model = Objects.requireNonNull(model, "model");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.gson = Objects.requireNonNull(gson, "gson");
        this.compactor = compactor;
        canonicalizer = new ToolResultNormalizer(gson);
    }

    public CompletableFuture<AgentResult> ask(
            AgentRequest request, Consumer<AgentEvent> events) {
        return askReserved(
                request,
                events,
                sessions.reserve(request.sessionKey(), request.requestId()));
    }

    public CompletableFuture<AgentResult> askWithHistory(
            AgentRequest request,
            List<ModelMessage> history,
            Consumer<AgentEvent> events) {
        return askReserved(
                request,
                events,
                sessions.reserveWithHistory(
                        request.sessionKey(), request.requestId(), history));
    }

    private CompletableFuture<AgentResult> askReserved(
            AgentRequest request,
            Consumer<AgentEvent> events,
            ToolResult<AgentSessionStore.Lease> reservation) {
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
        int protectedFromIndex = messages.size();
        messages.add(ModelMessage.userText(request.userMessage()));
        List<ModelMessage> completeMessages = List.copyOf(messages);
        if (compactor != null && !lease.checkpoints().isEmpty()) {
            for (int index = lease.checkpoints().size() - 1; index >= 0; index--) {
                var reused = compactor.reuse(
                        lease.checkpoints().get(index),
                        request.systemPrompt(),
                        messages,
                        protectedFromIndex,
                        tools.definitions());
                if (reused.isPresent()) {
                    transition(AgentState.MODEL_WAIT, trace, events);
                    return loop(
                                    request,
                                    lease,
                                    reused.orElseThrow().messages(),
                                    completeMessages,
                                    Map.of(),
                                    trace,
                                    events)
                            .exceptionally(throwable ->
                                    fail(request, lease, trace, events, throwable));
                }
            }
        }
        if (compactor != null
                && compactor.requiresCompaction(
                        request.systemPrompt(), messages, tools.definitions())) {
            transition(AgentState.COMPACTING, trace, events);
            return compactor.compact(
                            request.systemPrompt(),
                            messages,
                            protectedFromIndex,
                            tools.definitions(),
                            request.stream(),
                            request.sessionKey().schedulingKey(),
                            lease.cancellation())
                    .thenCompose(result -> {
                        if (result.checkpoint() != null) {
                            sessions.recordCheckpoint(lease, result.checkpoint());
                            events.accept(new AgentEvent.ContextCompacted(result.checkpoint()));
                        }
                        if (!result.successful()) {
                            throw new ModelClientException(new dev.tomewisp.model.ModelFailure(
                                    result.failureCode(), result.failureMessage(), null));
                        }
                        transition(AgentState.MODEL_WAIT, trace, events);
                        return loop(
                                request,
                                lease,
                                result.projection().messages(),
                                completeMessages,
                                Map.of(),
                                trace,
                                events);
                    })
                    .exceptionally(throwable -> fail(request, lease, trace, events, throwable));
        }
        transition(AgentState.MODEL_WAIT, trace, events);
        return loop(request, lease, messages, completeMessages, Map.of(), trace, events)
                .exceptionally(throwable -> fail(request, lease, trace, events, throwable));
    }

    private CompletableFuture<AgentResult> loop(
            AgentRequest request,
            AgentSessionStore.Lease lease,
            List<ModelMessage> messages,
            List<ModelMessage> completeMessages,
            Map<String, String> previousCallOutcomes,
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
                    List<ModelMessage> nextCompleteMessages = new ArrayList<>(completeMessages);
                    nextCompleteMessages.add(new ModelMessage(ModelRole.ASSISTANT, turn.content()));
                    if (turn.toolUses().isEmpty()) {
                        if (turn.text().isBlank()) {
                            throw new ModelClientException(new dev.tomewisp.model.ModelFailure(
                                    "model_protocol_error",
                                    "Model returned neither tool use nor final text",
                                    null));
                        }
                        transition(AgentState.COMPLETED, trace, events);
                        // Runtime memory keeps the successfully projected context. Durable guide
                        // history independently retains the original request/timeline projection.
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
                                    nextCompleteMessages,
                                    previousCallOutcomes,
                                    trace,
                                    events)
                            .thenCompose(outcome -> {
                                transition(AgentState.MODEL_WAIT, trace, events);
                                return loop(
                                        request,
                                        lease,
                                        outcome.messages(),
                                        outcome.completeMessages(),
                                        outcome.callOutcomes(),
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
            List<ModelMessage> completeMessages,
            Map<String, String> previousCallOutcomes,
            LiveAgentTraceRecorder trace,
            Consumer<AgentEvent> events) {
        CompletableFuture<ToolOutcome> chain = CompletableFuture.completedFuture(
                new ToolOutcome(
                        new ArrayList<>(messages),
                        new ArrayList<>(completeMessages),
                        Map.copyOf(previousCallOutcomes),
                        new ArrayList<>()));
        for (ModelContent.ToolUse call : calls) {
            chain = chain.thenCompose(outcome -> {
                lease.cancellation().throwIfCancelled();
                String exposedId = tools.canonicalToolId(call.name())
                        .orElse(AgentToolExecutor.UNKNOWN_TOOL_ID);
                String callKey = exposedId + ":" + canonical(call.input());
                String previousOutcome = outcome.callOutcomes().get(callKey);
                if (previousOutcome != null) {
                    if (REPEATED_CALL_SENTINEL.equals(previousOutcome)) {
                        throw new ModelClientException(new dev.tomewisp.model.ModelFailure(
                                "repeated_tool_call",
                                "Model ignored a no-new-information result and repeated the same tool call again",
                                null));
                    }
                    AgentToolResult repeated = noNewInformation(exposedId);
                    trace.toolCall(exposedId, call.input());
                    trace.toolResult(repeated);
                    List<ModelContent> results = new ArrayList<>(outcome.results());
                    results.add(new ModelContent.ToolResult(
                            call.id(), repeated.normalized(), true));
                    Map<String, String> updatedCallOutcomes =
                            new java.util.HashMap<>(outcome.callOutcomes());
                    updatedCallOutcomes.put(callKey, REPEATED_CALL_SENTINEL);
                    return CompletableFuture.completedFuture(new ToolOutcome(
                            outcome.messages(),
                            outcome.completeMessages(),
                            Map.copyOf(updatedCallOutcomes),
                            results));
                }
                events.accept(new AgentEvent.ToolStarted(
                        call.id(),
                        exposedId,
                        GuideToolInvocationPresentation.messages(exposedId, call.input())));
                trace.toolCall(exposedId, call.input());
                return tools.execute(call.name(), call.input(), request.context(), lease.cancellation())
                        .handle((rawResult, executionFailure) -> {
                            AgentToolResult result = executionFailure == null && rawResult != null
                                    ? new AgentToolResult(
                                            exposedId,
                                            rawResult.normalized(),
                                            rawResult.failure())
                                    : recoverToolFailure(
                                            exposedId,
                                            executionFailure,
                                            lease.cancellation());
                            trace.toolResult(result);
                            events.accept(new AgentEvent.ToolCompleted(
                                    call.id(),
                                    result.toolId(),
                                    result.failure(),
                                    result.normalized()));
                            List<ModelContent> results = new ArrayList<>(outcome.results());
                            results.add(new ModelContent.ToolResult(
                                    call.id(), result.normalized(), result.failure()));
                            String canonicalOutcome = canonical(result.normalized());
                            Map<String, String> updatedCallOutcomes =
                                    new java.util.HashMap<>(outcome.callOutcomes());
                            updatedCallOutcomes.put(callKey, canonicalOutcome);
                            return new ToolOutcome(
                                    outcome.messages(),
                                    outcome.completeMessages(),
                                    Map.copyOf(updatedCallOutcomes),
                                    results);
                        });
            });
        }
        return chain.thenApply(outcome -> {
            List<ModelMessage> updated = new ArrayList<>(outcome.messages());
            updated.add(new ModelMessage(ModelRole.USER, outcome.results()));
            List<ModelMessage> updatedComplete = new ArrayList<>(outcome.completeMessages());
            updatedComplete.add(new ModelMessage(ModelRole.USER, outcome.results()));
            return new ToolOutcome(
                    updated, updatedComplete, outcome.callOutcomes(), List.of());
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

    private JsonObject normalizedFailure(String code, String message) {
        return canonicalizer.normalize(new ToolResult.Failure<>(code, message), Object.class);
    }

    private AgentToolResult noNewInformation(String toolId) {
        return new AgentToolResult(
                toolId,
                normalizedFailure(
                        "no_new_information",
                        "This exact read-only call already completed against the same request snapshot; stop calling tools and answer from the existing evidence."),
                true);
    }

    private AgentToolResult recoverToolFailure(
            String toolId,
            Throwable throwable,
            dev.tomewisp.model.CancellationSignal cancellation) {
        cancellation.throwIfCancelled();
        Throwable cause = throwable == null
                ? new IllegalStateException("Tool executor completed without a result")
                : unwrap(throwable);
        if (cause instanceof ModelClientException exception
                && "agent_cancelled".equals(exception.failure().code())) {
            throw exception;
        }
        return new AgentToolResult(
                toolId,
                normalizedFailure(
                        "tool_failure",
                        "Tool execution failed; use the failure as a limitation and continue"),
                true);
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
            List<ModelMessage> completeMessages,
            Map<String, String> callOutcomes,
            List<ModelContent> results) {}
}
