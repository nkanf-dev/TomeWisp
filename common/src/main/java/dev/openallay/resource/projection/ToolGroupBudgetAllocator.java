package dev.openallay.resource.projection;

import dev.openallay.agent.tool.ModelToolResultView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Allocates one provider Tool-result group by complete semantic records. */
public final class ToolGroupBudgetAllocator {
    public record Allocation(
            List<ModelToolResultView> views,
            List<Boolean> complete,
            int estimatedTokens) {
        public Allocation {
            views = List.copyOf(views);
            complete = List.copyOf(complete);
            if (views.size() != complete.size() || estimatedTokens < 0) {
                throw new IllegalArgumentException("Tool group allocation is inconsistent");
            }
        }
    }

    public int minimumTokens(List<ModelToolResultView> views) {
        return prepare(views).stream().mapToInt(candidate -> bytes(candidate.receipt())).sum();
    }

    public Allocation allocate(List<ModelToolResultView> views, int availableTokens) {
        if (availableTokens < 0) {
            throw new IllegalArgumentException("availableTokens must be non-negative");
        }
        List<Candidate> candidates = prepare(views);
        int minimum = candidates.stream().mapToInt(candidate -> bytes(candidate.receipt())).sum();
        if (minimum > availableTokens) {
            throw new IllegalArgumentException(
                    "Tool result receipts require " + minimum + " tokens but only "
                            + availableTokens + " remain");
        }
        int remaining = availableTokens - minimum;
        int[] included = new int[candidates.size()];
        boolean madeProgress;
        do {
            madeProgress = false;
            for (int index = 0; index < candidates.size(); index++) {
                Candidate candidate = candidates.get(index);
                if (included[index] >= candidate.units().size()) {
                    continue;
                }
                String next = candidate.units().get(included[index]);
                // One separator joins the selected prefix to the mandatory receipt.
                int cost = bytes(next) + 1;
                if (cost <= remaining) {
                    included[index]++;
                    remaining -= cost;
                    madeProgress = true;
                }
            }
        } while (madeProgress);

        ArrayList<ModelToolResultView> projected = new ArrayList<>(candidates.size());
        ArrayList<Boolean> complete = new ArrayList<>(candidates.size());
        int estimated = 0;
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            boolean all = included[index] == candidate.units().size();
            String text;
            if (all) {
                text = candidate.original().text();
            } else if (included[index] == 0) {
                text = candidate.receipt();
            } else {
                text = String.join("\n", candidate.units().subList(0, included[index]))
                        + "\n" + candidate.receipt();
            }
            estimated += bytes(text);
            List<String> projectedUnits = all
                    ? candidate.original().semanticUnits()
                    : included[index] == 0
                            ? List.of(candidate.receipt())
                            : List.copyOf(candidate.units().subList(0, included[index]));
            projected.add(new ModelToolResultView(
                    text, candidate.original().receipts(), projectedUnits, text.length()));
            complete.add(all);
        }
        return new Allocation(projected, complete, estimated);
    }

    private static List<Candidate> prepare(List<ModelToolResultView> views) {
        views = List.copyOf(Objects.requireNonNull(views, "views"));
        if (views.isEmpty()) {
            return List.of();
        }
        ArrayList<Candidate> candidates = new ArrayList<>(views.size());
        for (ModelToolResultView view : views) {
            candidates.add(new Candidate(view, view.semanticUnits(), receipt(view)));
        }
        return List.copyOf(candidates);
    }

    private static String receipt(ModelToolResultView view) {
        ArrayList<String> lines = new ArrayList<>();
        String status = view.text().lines()
                .filter(line -> line.startsWith("status:"))
                .findFirst()
                .orElse("status: available");
        lines.add(status);
        if (view.receipts().isEmpty()) {
            lines.add("content: omitted from this dispatch because the complete semantic unit does not fit");
            lines.add("next: repeat the read with a narrower path, field selection, filter, or range");
            return String.join("\n", lines);
        }
        Set<String> seenPaths = new HashSet<>();
        for (ResourceReceipt receipt : view.receipts()) {
            String path = receipt.resultPath() == null ? "unavailable" : receipt.resultPath().toString();
            if (seenPaths.add(path)) {
                lines.add("resource: " + path);
            }
            lines.add("generation: " + receipt.generationId());
            lines.add("kind: " + receipt.kind());
            lines.add("authority: " + receipt.authority());
            lines.add("completeness: " + receipt.completeness());
            lines.add("returned: " + receipt.returned()
                    + (receipt.total() == null ? "" : "/" + receipt.total()));
            if (receipt.fromInclusive() != null) {
                lines.add("range: " + receipt.fromInclusive() + ".." + receipt.toExclusive());
            } else if (!receipt.recordIdentities().isEmpty()) {
                lines.add("record_identities: " + String.join(",", receipt.recordIdentities()));
            }
            if (!receipt.fields().isEmpty()) {
                lines.add("fields: " + String.join(",", receipt.fields()));
            }
            if (receipt.nextCursor() != null && !receipt.nextCursor().isBlank()) {
                lines.add("next_cursor: " + receipt.nextCursor());
                lines.add("next: call resource_read with cursor=" + receipt.nextCursor()
                        + " and optionally narrower fields");
            } else {
                lines.add("next: call resource_read on " + path + " with narrower fields or range");
            }
        }
        return String.join("\n", lines);
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private record Candidate(ModelToolResultView original, List<String> units, String receipt) {}
}
