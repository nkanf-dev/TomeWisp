package dev.openallay.agent.context;

import dev.openallay.agent.tool.ModelToolResultView;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelToolDefinition;
import dev.openallay.resource.projection.ResourceReceipt;
import dev.openallay.resource.projection.ToolGroupBudgetAllocator;
import dev.openallay.resource.vfs.ResourcePath;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** Builds the bounded provider-neutral request projection before every dispatch. */
public final class ContextAssembler {
    public record Assembly(ContextProjection projection, boolean fits, int inputLimitTokens) {
        public Assembly {
            Objects.requireNonNull(projection, "projection");
            if (inputLimitTokens <= 0) {
                throw new IllegalArgumentException("inputLimitTokens must be positive");
            }
            if (fits != (projection.estimatedTokens() <= inputLimitTokens)) {
                throw new IllegalArgumentException("Context assembly fit flag is inconsistent");
            }
        }
    }

    private final ContextTokenEstimator estimator;
    private final ToolResultContextReducer historicalReducer;
    private final ToolGroupBudgetAllocator allocator;
    private final ContextBudget budget;

    public ContextAssembler(
            ContextTokenEstimator estimator,
            ToolResultContextReducer historicalReducer,
            ToolGroupBudgetAllocator allocator,
            ContextBudget budget) {
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.historicalReducer = Objects.requireNonNull(historicalReducer, "historicalReducer");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public Assembly assemble(
            String systemPrompt,
            List<ModelMessage> messages,
            int protectedFromIndex,
            List<ModelToolDefinition> tools) {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        List<ContextStructure.Unit> units = ContextStructure.units(messages);
        ContextStructure.requireBoundary(units, protectedFromIndex, messages.size());

        ContextProjection historical = historicalReducer.reduce(messages, protectedFromIndex);
        List<ModelMessage> edited = historical.messages();
        List<ModelContent.ToolResult> activeResults = activeResults(edited, protectedFromIndex);
        if (activeResults.isEmpty()) {
            int estimate = estimator.estimate(systemPrompt, edited, tools);
            return new Assembly(historical.withEstimate(estimate), estimate <= budget.inputTokens(),
                    budget.inputTokens());
        }

        List<ModelToolResultView> views = activeResults.stream()
                .map(ContextAssembler::view)
                .toList();
        int minimum = allocator.minimumTokens(views);
        ToolGroupBudgetAllocator.Allocation receipts = allocator.allocate(views, minimum);
        List<ModelMessage> baseline = replaceActiveResults(
                edited, protectedFromIndex, activeResults, receipts.views());
        int baselineEstimate = estimator.estimate(systemPrompt, baseline, tools);
        if (baselineEstimate > budget.inputTokens()) {
            return new Assembly(new ContextProjection(
                    baseline, ContextProjection.Kind.CURRENT_RESULTS_PROJECTED, baselineEstimate),
                    false, budget.inputTokens());
        }

        int availableForViews = Math.addExact(minimum, budget.inputTokens() - baselineEstimate);
        ToolGroupBudgetAllocator.Allocation allocation = allocator.allocate(views, availableForViews);
        List<ModelMessage> projected = replaceActiveResults(
                edited, protectedFromIndex, activeResults, allocation.views());
        int estimate = estimator.estimate(systemPrompt, projected, tools);
        ContextProjection.Kind kind = projected.equals(messages)
                ? ContextProjection.Kind.ORIGINAL
                : projected.equals(edited)
                        ? historical.kind()
                        : ContextProjection.Kind.CURRENT_RESULTS_PROJECTED;
        return new Assembly(new ContextProjection(projected, kind, estimate),
                estimate <= budget.inputTokens(), budget.inputTokens());
    }

    public ContextBudget budget() {
        return budget;
    }

    private static List<ModelContent.ToolResult> activeResults(
            List<ModelMessage> messages, int protectedFromIndex) {
        ArrayList<ModelContent.ToolResult> results = new ArrayList<>();
        for (int index = protectedFromIndex; index < messages.size(); index++) {
            for (ModelContent content : messages.get(index).content()) {
                if (content instanceof ModelContent.ToolResult result) {
                    results.add(result);
                }
            }
        }
        return List.copyOf(results);
    }

    private static List<ModelMessage> replaceActiveResults(
            List<ModelMessage> messages,
            int protectedFromIndex,
            List<ModelContent.ToolResult> originals,
            List<ModelToolResultView> projected) {
        if (originals.size() != projected.size()) {
            throw new IllegalArgumentException("Projected Tool result count changed");
        }
        Iterator<ModelToolResultView> replacements = projected.iterator();
        ArrayList<ModelMessage> output = new ArrayList<>(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            ModelMessage message = messages.get(index);
            if (index < protectedFromIndex) {
                output.add(message);
                continue;
            }
            ArrayList<ModelContent> content = new ArrayList<>(message.content().size());
            boolean changed = false;
            for (ModelContent item : message.content()) {
                if (item instanceof ModelContent.ToolResult result) {
                    ModelToolResultView replacement = replacements.next();
                    String text = replacement.text();
                    content.add(new ModelContent.ToolResult(
                            result.toolUseId(), text, result.receiptPath(), result.receipts(),
                            replacement.semanticUnits(), result.error()));
                    changed |= !text.equals(result.text());
                } else {
                    content.add(item);
                }
            }
            output.add(changed ? new ModelMessage(message.role(), content) : message);
        }
        if (replacements.hasNext()) {
            throw new IllegalStateException("Projected Tool results were not consumed");
        }
        return List.copyOf(output);
    }

    private static ModelToolResultView view(ModelContent.ToolResult result) {
        if (result.receipts().isEmpty() && result.receiptPath() != null) {
            ResourceReceipt restored = new ResourceReceipt(
                    ResourcePath.parse(result.receiptPath()),
                    "restored",
                    "tool_result",
                    0,
                    null,
                    List.of(),
                    null,
                    "unknown",
                    "unknown",
                    null,
                    null,
                    List.of());
            return new ModelToolResultView(
                    result.text(), List.of(restored), result.semanticUnits(), result.text().length());
        }
        return new ModelToolResultView(
                result.text(), result.receipts(), result.semanticUnits(), result.text().length());
    }
}
