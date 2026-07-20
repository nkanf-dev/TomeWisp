package dev.openallay.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.openallay.agent.session.AgentSessionStore;
import dev.openallay.agent.context.ContextCompactor;
import dev.openallay.agent.tool.AgentToolExecutor;
import dev.openallay.agent.tool.AgentToolResult;
import dev.openallay.agent.trace.LiveAgentTrace;
import dev.openallay.agent.trace.LiveAgentTraceRecorder;
import dev.openallay.model.ModelClient;
import dev.openallay.model.ModelClientException;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelEvent;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRequest;
import dev.openallay.model.ModelRole;
import dev.openallay.model.ModelTurn;
import dev.openallay.guide.GuideToolInvocationPresentation;
import dev.openallay.tool.ToolResult;
import dev.openallay.trace.replay.ToolResultNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public final class GameGuideAgent {
    private static final String NO_PROGRESS_ONCE = "\u0000no_new_information_once";
    private static final String NO_PROGRESS_TWICE = "\u0000final_synthesis_requested";
    private static final String FACT_PREFIX = "\u0000fact:";
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
                                    Math.max(0, reused.orElseThrow().messages().size()
                                            - (messages.size() - protectedFromIndex)),
                                    trace,
                                    events)
                            .exceptionally(throwable ->
                                    fail(request, lease, trace, events, throwable));
                }
            }
        }
        if (compactor != null
                && compactor.requiresCompaction(
                        request.systemPrompt(), messages, protectedFromIndex, tools.definitions())) {
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
                            throw new ModelClientException(new dev.openallay.model.ModelFailure(
                                    result.failureCode(), result.failureMessage(), null));
                        }
                        transition(AgentState.MODEL_WAIT, trace, events);
                        return loop(
                                request,
                                lease,
                                result.projection().messages(),
                                completeMessages,
                                Map.of(),
                                Math.max(0, result.projection().messages().size()
                                        - (messages.size() - protectedFromIndex)),
                                trace,
                                events);
                    })
                    .exceptionally(throwable -> fail(request, lease, trace, events, throwable));
        }
        transition(AgentState.MODEL_WAIT, trace, events);
        return loop(request, lease, messages, completeMessages, Map.of(), protectedFromIndex, trace, events)
                .exceptionally(throwable -> fail(request, lease, trace, events, throwable));
    }

    private CompletableFuture<AgentResult> loop(
            AgentRequest request,
            AgentSessionStore.Lease lease,
            List<ModelMessage> messages,
            List<ModelMessage> completeMessages,
            Map<String, String> previousCallOutcomes,
            int protectedFromIndex,
            LiveAgentTraceRecorder trace,
            Consumer<AgentEvent> events) {
        lease.cancellation().throwIfCancelled();
        dev.openallay.agent.context.ContextAssembler.Assembly assembly = compactor == null
                ? null
                : compactor.assemble(
                        request.systemPrompt(), messages, protectedFromIndex, tools.definitions());
        if (assembly != null && !assembly.fits()) {
            transition(AgentState.COMPACTING, trace, events);
            int currentSuffixSize = messages.size() - protectedFromIndex;
            return compactor.compact(
                            request.systemPrompt(),
                            assembly.projection().messages(),
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
                            throw new ModelClientException(new dev.openallay.model.ModelFailure(
                                    result.failureCode(), result.failureMessage(), null));
                        }
                        transition(AgentState.MODEL_WAIT, trace, events);
                        List<ModelMessage> projected = result.projection().messages();
                        return loop(
                                request,
                                lease,
                                projected,
                                completeMessages,
                                previousCallOutcomes,
                                Math.max(0, projected.size() - currentSuffixSize),
                                trace,
                                events);
                    });
        }
        List<ModelMessage> dispatchMessages = assembly == null
                ? messages
                : assembly.projection().messages();
        ModelRequest modelRequest = new ModelRequest(
                request.systemPrompt(),
                dispatchMessages,
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
                    List<ModelMessage> nextMessages = new ArrayList<>(dispatchMessages);
                    nextMessages.add(new ModelMessage(ModelRole.ASSISTANT, turn.content()));
                    List<ModelMessage> nextCompleteMessages = new ArrayList<>(completeMessages);
                    nextCompleteMessages.add(new ModelMessage(ModelRole.ASSISTANT, turn.content()));
                    if (turn.toolUses().isEmpty()) {
                        if (turn.text().isBlank()) {
                            throw new ModelClientException(new dev.openallay.model.ModelFailure(
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
                                        protectedFromIndex,
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
        lease.cancellation().throwIfCancelled();
        List<PendingToolCall> pending = new ArrayList<>();
        Map<String, CompletableFuture<AgentToolResult>> firstCalls = new java.util.LinkedHashMap<>();
        java.util.Set<String> duplicatedThisTurn = new java.util.HashSet<>();
        for (ModelContent.ToolUse call : calls) {
            lease.cancellation().throwIfCancelled();
            String exposedId = tools.canonicalToolId(call.name())
                    .orElse(AgentToolExecutor.UNKNOWN_TOOL_ID);
            String callKey = exposedId + ":" + canonical(call.input());
            String previousOutcome = previousCallOutcomes.get(callKey);
            if (NO_PROGRESS_TWICE.equals(previousOutcome)) {
                throw new ModelClientException(new dev.openallay.model.ModelFailure(
                        "repeated_tool_call",
                        "Model repeated a no-progress Tool call after explicit final-synthesis guidance",
                        null));
            }
            trace.toolCall(exposedId, call.input());
            if (previousOutcome != null || firstCalls.containsKey(callKey)) {
                duplicatedThisTurn.add(callKey);
                boolean finalSynthesis = NO_PROGRESS_ONCE.equals(previousOutcome);
                pending.add(new PendingToolCall(
                        call,
                        exposedId,
                        callKey,
                        false,
                        CompletableFuture.completedFuture(
                                noNewInformation(exposedId, finalSynthesis))));
                continue;
            }
            events.accept(new AgentEvent.ToolStarted(
                    call.id(),
                    exposedId,
                    GuideToolInvocationPresentation.messages(exposedId, call.input())));
            CompletableFuture<AgentToolResult> execution = tools
                    .execute(call.name(), call.input(), request.context(), lease.cancellation())
                    .handle((rawResult, executionFailure) -> executionFailure == null && rawResult != null
                            ? rawResult.withToolId(exposedId)
                            : recoverToolFailure(
                                    exposedId, executionFailure, lease.cancellation()));
            firstCalls.put(callKey, execution);
            pending.add(new PendingToolCall(call, exposedId, callKey, true, execution));
        }
        CompletableFuture<?>[] futures = pending.stream()
                .map(PendingToolCall::result)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures).thenApply(ignored -> {
            lease.cancellation().throwIfCancelled();
            List<ModelContent> results = new ArrayList<>();
            Map<String, String> updatedCallOutcomes =
                    new java.util.HashMap<>(previousCallOutcomes);
            for (PendingToolCall item : pending) {
                AgentToolResult result = item.result().join();
                trace.toolResult(result);
                if (item.executed()) {
                    events.accept(new AgentEvent.ToolCompleted(
                            item.call().id(),
                            result.toolId(),
                            result.failure(),
                            result.normalized(),
                            result.uiReference(),
                            result.diagnostics()));
                }
                String information = informationFingerprint(result);
                boolean newInformation = !updatedCallOutcomes.containsKey(FACT_PREFIX + information);
                String modelText = result.modelView().text();
                if (item.executed() && !newInformation) {
                    modelText += "\nprogress: no_new_information\n"
                            + "guidance: answer from the existing resource evidence; do not repeat this lookup";
                }
                results.add(new ModelContent.ToolResult(
                        item.call().id(),
                        modelText,
                        receiptPath(result),
                        result.modelView().receipts(),
                        result.modelView().semanticUnits(),
                        result.failure()));
                updatedCallOutcomes.put(FACT_PREFIX + information, "seen");
                updatedCallOutcomes.put(
                        item.callKey(),
                        duplicatedThisTurn.contains(item.callKey())
                                ? (NO_PROGRESS_ONCE.equals(previousCallOutcomes.get(item.callKey()))
                                        ? NO_PROGRESS_TWICE
                                        : NO_PROGRESS_ONCE)
                                : (newInformation
                                        ? canonical(result.normalized())
                                        : NO_PROGRESS_ONCE));
            }
            List<ModelMessage> updated = new ArrayList<>(messages);
            updated.add(new ModelMessage(ModelRole.USER, results));
            List<ModelMessage> updatedComplete = new ArrayList<>(completeMessages);
            updatedComplete.add(new ModelMessage(ModelRole.USER, results));
            return new ToolOutcome(
                    updated, updatedComplete, Map.copyOf(updatedCallOutcomes), List.of());
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

    private AgentToolResult noNewInformation(String toolId, boolean finalSynthesis) {
        return new AgentToolResult(
                toolId,
                normalizedFailure(
                        "no_new_information",
                        finalSynthesis
                                ? "No new facts are available. Produce the final answer now from the retained evidence without another Tool call."
                                : "This call adds no new resource path, field, range, or generation. Answer from the existing evidence or make one materially narrower read."),
                true);
    }

    private String informationFingerprint(AgentToolResult result) {
        String semantic = result.modelView().text().lines()
                .filter(line -> line.startsWith("path:")
                        || line.startsWith("generation:")
                        || line.startsWith("authority:")
                        || line.startsWith("completeness:")
                        || line.startsWith("fields:")
                        || line.startsWith("range:")
                        || line.startsWith("record_identities:")
                        || line.startsWith("returned:"))
                .collect(java.util.stream.Collectors.joining("\n"));
        if (!semantic.isBlank()) {
            return semantic;
        }
        if (!result.modelView().receipts().isEmpty()) {
            return result.modelView().receipts().stream()
                    .map(receipt -> String.join("|",
                            receipt.generationId(),
                            receipt.kind(),
                            receipt.authority(),
                            receipt.completeness(),
                            Long.toString(receipt.returned()),
                            receipt.total() == null ? "" : Long.toString(receipt.total()),
                            String.join(",", receipt.fields()),
                            receipt.fromInclusive() == null ? "" : Long.toString(receipt.fromInclusive()),
                            receipt.toExclusive() == null ? "" : Long.toString(receipt.toExclusive()),
                            String.join(",", receipt.recordIdentities())))
                    .sorted()
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        return "legacy:" + canonical(result.normalized());
    }

    private static String receiptPath(AgentToolResult result) {
        if (!result.modelView().receipts().isEmpty()
                && result.modelView().receipts().getFirst().resultPath() != null) {
            return result.modelView().receipts().getFirst().resultPath().toString();
        }
        return result.uiReference().resultPath() == null
                ? null
                : result.uiReference().resultPath().toString();
    }

    private AgentToolResult recoverToolFailure(
            String toolId,
            Throwable throwable,
            dev.openallay.model.CancellationSignal cancellation) {
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

    private record PendingToolCall(
            ModelContent.ToolUse call,
            String exposedId,
            String callKey,
            boolean executed,
            CompletableFuture<AgentToolResult> result) {}
}
