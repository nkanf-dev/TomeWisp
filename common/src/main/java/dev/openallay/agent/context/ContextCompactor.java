package dev.openallay.agent.context;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelClient;
import dev.openallay.model.ModelClientException;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRequest;
import dev.openallay.model.ModelToolDefinition;
import dev.openallay.resource.projection.ToolGroupBudgetAllocator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
            You compact OpenAllay conversation memory. Return exactly one JSON object with arrays
            goals, preferences, completedTopics, currentTasks, decisions, unresolvedQuestions,
            and evidenceReferences. Do not add facts, treat summaries as evidence, or include
            hidden reasoning. Preserve stable evidence references verbatim.
            """;
    private static final String DERIVED_PREFIX =
            "[OpenAllay derived conversation memory; NOT factual evidence]\n";

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
    private final ContextAssembler assembler;
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
        this.assembler = new ContextAssembler(
                estimator, reducer, new ToolGroupBudgetAllocator(), budget);
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
        ContextAssembler.Assembly assembly = assembler.assemble(
                systemPrompt, messages, protectedFromIndex, requestTools);
        ContextProjection reduced = assembly.projection();
        int reducedEstimate = reduced.estimatedTokens();
        if (assembly.fits()) {
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
                            ContextSourceHash.compute(
                                    gson, deterministic.messages().subList(
                                            0, prefix.toIndexExclusive())),
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
        return checkpoint.sourceHash().equals(ContextSourceHash.compute(gson, source.subList(
                checkpoint.sourceFromIndex(), checkpoint.sourceToIndexExclusive())));
    }

    public boolean requiresCompaction(
            String systemPrompt,
            List<ModelMessage> messages,
            List<ModelToolDefinition> tools) {
        return requiresCompaction(systemPrompt, messages, 0, tools);
    }

    public boolean requiresCompaction(
            String systemPrompt,
            List<ModelMessage> messages,
            int protectedFromIndex,
            List<ModelToolDefinition> tools) {
        return !assembler.assemble(systemPrompt, messages, protectedFromIndex, tools).fits();
    }

    public ContextAssembler.Assembly assemble(
            String systemPrompt,
            List<ModelMessage> messages,
            int protectedFromIndex,
            List<ModelToolDefinition> tools) {
        return assembler.assemble(systemPrompt, messages, protectedFromIndex, tools);
    }

    public Optional<ContextProjection> reuse(
            ContextCheckpoint checkpoint,
            String systemPrompt,
            List<ModelMessage> messages,
            int protectedFromIndex,
            List<ModelToolDefinition> tools) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        messages = List.copyOf(messages);
        if (checkpoint.status() != ContextCheckpoint.Status.SUCCEEDED
                || checkpoint.promptVersion() != PROMPT_VERSION
                || checkpoint.schemaVersion() != SCHEMA_VERSION
                || checkpoint.sourceFromIndex() != 0
                || checkpoint.sourceToIndexExclusive() > protectedFromIndex
                || !matches(checkpoint, messages.subList(0, protectedFromIndex))) {
            return Optional.empty();
        }
        ArrayList<ModelMessage> projected = new ArrayList<>();
        projected.add(ModelMessage.userText(DERIVED_PREFIX + checkpoint.summary()));
        projected.addAll(messages.subList(
                checkpoint.sourceToIndexExclusive(), messages.size()));
        int estimate = estimator.estimate(systemPrompt, projected, tools);
        if (estimate > budget.inputTokens()) {
            return Optional.empty();
        }
        return Optional.of(new ContextProjection(
                projected, ContextProjection.Kind.SUMMARIZED, estimate));
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
            String serialized = serializeForSummary(safe);
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

    private static String serializeForSummary(List<ModelMessage> messages) {
        StringBuilder output = new StringBuilder();
        for (ModelMessage message : messages) {
            output.append("message: ").append(message.role().name().toLowerCase()).append('\n');
            for (ModelContent content : message.content()) {
                switch (content) {
                    case ModelContent.Text text -> appendIndented(output, "text", text.text());
                    case ModelContent.ToolUse use -> {
                        output.append("  tool_use: ").append(use.name()).append('\n');
                        output.append("    id: ").append(use.id()).append('\n');
                        output.append("    arguments:\n");
                        appendJson(output, use.input(), 3, null);
                    }
                    case ModelContent.ToolResult result -> {
                        output.append("  tool_result: ").append(result.toolUseId()).append('\n');
                        output.append("    error: ").append(result.error()).append('\n');
                        if (result.receiptPath() != null) {
                            output.append("    resource: ").append(result.receiptPath()).append('\n');
                        }
                        appendIndented(output, "content", result.text(), 2);
                    }
                    case ModelContent.Reasoning ignored -> {
                        // summarySafe removes this; keep the serializer closed if called directly.
                    }
                }
            }
        }
        return output.toString().stripTrailing();
    }

    private static void appendJson(
            StringBuilder output, JsonElement value, int depth, String field) {
        String indent = "  ".repeat(depth);
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) {
            output.append(indent);
            if (field != null) output.append(field).append(": ");
            output.append(value == null || value.isJsonNull()
                    ? "null" : value.getAsJsonPrimitive().getAsString()).append('\n');
            return;
        }
        if (value.isJsonArray()) {
            if (field != null) output.append(indent).append(field).append(":\n");
            int childDepth = field == null ? depth : depth + 1;
            for (JsonElement item : value.getAsJsonArray()) {
                output.append("  ".repeat(childDepth)).append("-\n");
                appendJson(output, item, childDepth + 1, null);
            }
            return;
        }
        if (field != null) output.append(indent).append(field).append(":\n");
        int childDepth = field == null ? depth : depth + 1;
        value.getAsJsonObject().keySet().stream().sorted()
                .forEach(key -> appendJson(output, value.getAsJsonObject().get(key), childDepth, key));
    }

    private static void appendIndented(StringBuilder output, String field, String text) {
        appendIndented(output, field, text, 1);
    }

    private static void appendIndented(
            StringBuilder output, String field, String text, int depth) {
        output.append("  ".repeat(depth)).append(field).append(":\n");
        text.lines().forEach(line -> output.append("  ".repeat(depth + 1)).append(line).append('\n'));
    }

    private ContextCheckpoint failedCheckpoint(
            List<ModelMessage> messages, int from, int to, String code, String message, int estimate) {
        int safeTo = Math.min(Math.max(from + 1, to), messages.size());
        return new ContextCheckpoint(
                UUID.randomUUID(), from, safeTo,
                ContextSourceHash.compute(gson, messages.subList(from, safeTo)),
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
