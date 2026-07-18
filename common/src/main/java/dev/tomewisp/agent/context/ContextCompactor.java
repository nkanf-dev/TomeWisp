package dev.tomewisp.agent.context;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.ModelClientException;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.model.ModelRequest;
import dev.tomewisp.model.ModelToolDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ContextCompactor {
    public static final int PROMPT_VERSION = 1;
    public static final int SCHEMA_VERSION = 1;
    private static final Set<String> SUMMARY_FIELDS = Set.of(
            "goals", "preferences", "completedTopics", "currentTasks", "decisions",
            "unresolvedQuestions", "evidenceReferences");
    private static final String SUMMARY_SYSTEM = """
            You compact TomeWisp conversation memory. Return exactly one JSON object with arrays
            goals, preferences, completedTopics, currentTasks, decisions, unresolvedQuestions,
            and evidenceReferences. Do not add facts, treat summaries as evidence, or include
            hidden reasoning. Preserve stable evidence references verbatim.
            """;
    private static final String DERIVED_PREFIX =
            "[TomeWisp derived conversation memory; NOT factual evidence]\n";

    public record Result(
            ContextProjection projection,
            ContextCheckpoint checkpoint,
            String failureCode,
            String failureMessage) {
        public Result {
            boolean success = projection != null;
            if (success == (failureCode != null || failureMessage != null)) {
                throw new IllegalArgumentException("compaction result success/failure is inconsistent");
            }
            if (!success && checkpoint == null) {
                throw new IllegalArgumentException("compaction failure requires a checkpoint");
            }
        }

        public boolean successful() {
            return projection != null;
        }
    }

    private final ModelClient model;
    private final Gson gson;
    private final ContextTokenEstimator estimator;
    private final ToolResultContextReducer reducer;
    private final ContextBudget budget;
    private final String modelIdentifier;
    private final Clock clock;

    public ContextCompactor(
            ModelClient model,
            Gson gson,
            ContextTokenEstimator estimator,
            ToolResultContextReducer reducer,
            ContextBudget budget,
            String modelIdentifier,
            Clock clock) {
        this.model = Objects.requireNonNull(model, "model");
        this.gson = Objects.requireNonNull(gson, "gson");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.reducer = Objects.requireNonNull(reducer, "reducer");
        this.budget = Objects.requireNonNull(budget, "budget");
        if (modelIdentifier == null || modelIdentifier.isBlank()) {
            throw new IllegalArgumentException("modelIdentifier is required");
        }
        this.modelIdentifier = modelIdentifier;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletableFuture<Result> compact(
            String systemPrompt,
            List<ModelMessage> messages,
            int protectedFromIndex,
            List<ModelToolDefinition> tools,
            boolean stream,
            String schedulingKey,
            CancellationSignal cancellation) {
        cancellation.throwIfCancelled();
        messages = List.copyOf(messages);
        List<ModelToolDefinition> requestTools = List.copyOf(tools);
        List<ContextStructure.Unit> units = ContextStructure.units(messages);
        ContextStructure.requireBoundary(units, protectedFromIndex, messages.size());
        int originalEstimate = estimator.estimate(systemPrompt, messages, requestTools);
        if (originalEstimate <= budget.inputTokens()) {
            return CompletableFuture.completedFuture(new Result(
                    new ContextProjection(messages, ContextProjection.Kind.ORIGINAL, originalEstimate),
                    null, null, null));
        }
        ContextProjection reduced = reducer.reduce(messages, protectedFromIndex);
        int reducedEstimate = estimator.estimate(systemPrompt, reduced.messages(), requestTools);
        reduced = reduced.withEstimate(reducedEstimate);
        if (reducedEstimate <= budget.inputTokens()) {
            return CompletableFuture.completedFuture(new Result(reduced, null, null, null));
        }
        Prefix prefix = summaryPrefix(reduced.messages(), protectedFromIndex, schedulingKey);
        if (prefix == null) {
            ContextCheckpoint failure = failedCheckpoint(
                    reduced.messages(), 0, Math.max(1, protectedFromIndex),
                    "summary_input_over_budget", "No structural summary prefix fits the model budget",
                    reducedEstimate);
            return CompletableFuture.completedFuture(new Result(
                    null, failure, "context_compaction_failed", failure.failureMessage()));
        }
        ModelRequest summaryRequest = new ModelRequest(
                SUMMARY_SYSTEM,
                List.of(ModelMessage.userText(prefix.serialized())),
                List.of(),
                false,
                schedulingKey);
        ContextProjection deterministic = reduced;
        return model.complete(summaryRequest, ignored -> {}, cancellation)
                .handle((turn, throwable) -> {
                    cancellation.throwIfCancelled();
                    if (throwable != null) {
                        Throwable cause = unwrap(throwable);
                        String code = cause instanceof ModelClientException modelFailure
                                ? modelFailure.failure().code() : "summary_failure";
                        ContextCheckpoint failure = failedCheckpoint(
                                deterministic.messages(), 0, prefix.toIndexExclusive(), code,
                                safeMessage(cause), deterministic.estimatedTokens());
                        return new Result(null, failure, "context_compaction_failed",
                                "Context summary failed: " + code);
                    }
                    JsonObject summary;
                    try {
                        summary = parseSummary(turn.text());
                    } catch (RuntimeException malformed) {
                        ContextCheckpoint failure = failedCheckpoint(
                                deterministic.messages(), 0, prefix.toIndexExclusive(),
                                "summary_malformed", "Summary response did not match schema",
                                deterministic.estimatedTokens());
                        return new Result(null, failure, "context_compaction_failed",
                                failure.failureMessage());
                    }
                    ArrayList<ModelMessage> projected = new ArrayList<>();
                    projected.add(ModelMessage.userText(DERIVED_PREFIX + summary));
                    projected.addAll(deterministic.messages().subList(
                            prefix.toIndexExclusive(), deterministic.messages().size()));
                    int estimate = estimator.estimate(systemPrompt, projected, requestTools);
                    if (estimate > budget.inputTokens()) {
                        ContextCheckpoint failure = failedCheckpoint(
                                deterministic.messages(), 0, prefix.toIndexExclusive(),
                                "summary_projection_over_budget",
                                "Summary projection still exceeds the model budget", estimate);
                        return new Result(null, failure, "context_compaction_failed",
                                failure.failureMessage());
                    }
                    String encoded = summary.toString();
                    ContextCheckpoint checkpoint = new ContextCheckpoint(
                            UUID.randomUUID(), 0, prefix.toIndexExclusive(),
                            hash(deterministic.messages().subList(0, prefix.toIndexExclusive())),
                            modelIdentifier, PROMPT_VERSION, SCHEMA_VERSION, clock.instant(),
                            ContextCheckpoint.Status.SUCCEEDED, encoded, null, null, estimate);
                    return new Result(new ContextProjection(
                            projected, ContextProjection.Kind.SUMMARIZED, estimate),
                            checkpoint, null, null);
                });
    }

    public boolean matches(ContextCheckpoint checkpoint, List<ModelMessage> source) {
        if (checkpoint.status() != ContextCheckpoint.Status.SUCCEEDED
                || checkpoint.sourceToIndexExclusive() > source.size()) return false;
        return checkpoint.sourceHash().equals(hash(source.subList(
                checkpoint.sourceFromIndex(), checkpoint.sourceToIndexExclusive())));
    }

    private Prefix summaryPrefix(
            List<ModelMessage> messages,
            int protectedFromIndex,
            String schedulingKey) {
        List<ContextStructure.Unit> units = ContextStructure.units(messages);
        int bestEnd = -1;
        String best = null;
        for (ContextStructure.Unit unit : units) {
            if (unit.toIndexExclusive() > protectedFromIndex) break;
            List<ModelMessage> safe = ContextStructure.summarySafe(
                    messages.subList(0, unit.toIndexExclusive()));
            String serialized = gson.toJson(safe);
            ModelRequest request = new ModelRequest(
                    SUMMARY_SYSTEM, List.of(ModelMessage.userText(serialized)), List.of(), false,
                    schedulingKey);
            int estimate = estimator.estimate(
                    request.systemPrompt(), request.messages(), request.tools());
            if (estimate > budget.inputTokens()) break;
            bestEnd = unit.toIndexExclusive();
            best = serialized;
        }
        return bestEnd < 1 ? null : new Prefix(bestEnd, best);
    }

    private ContextCheckpoint failedCheckpoint(
            List<ModelMessage> messages, int from, int to, String code, String message, int estimate) {
        int safeTo = Math.min(Math.max(from + 1, to), messages.size());
        return new ContextCheckpoint(
                UUID.randomUUID(), from, safeTo, hash(messages.subList(from, safeTo)),
                modelIdentifier, PROMPT_VERSION, SCHEMA_VERSION, clock.instant(),
                ContextCheckpoint.Status.FAILED, null, code, message, Math.max(0, estimate));
    }

    private static JsonObject parseSummary(String text) {
        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject() || !parsed.getAsJsonObject().keySet().equals(SUMMARY_FIELDS)) {
            throw new IllegalArgumentException("summary schema mismatch");
        }
        JsonObject object = parsed.getAsJsonObject();
        for (String field : SUMMARY_FIELDS) {
            if (!object.get(field).isJsonArray()) throw new IllegalArgumentException("summary field");
            for (JsonElement item : object.getAsJsonArray(field)) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("summary item");
                }
            }
        }
        return object.deepCopy();
    }

    private String hash(List<ModelMessage> messages) {
        try {
            byte[] bytes = gson.toJson(ContextStructure.summarySafe(messages))
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record Prefix(int toIndexExclusive, String serialized) {}
}
