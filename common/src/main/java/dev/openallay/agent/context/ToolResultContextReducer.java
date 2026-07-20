package dev.openallay.agent.context;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelMessage;
import dev.openallay.resource.projection.ResourceReceipt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structurally replaces old model-facing Tool bodies with stable semantic receipts. */
public final class ToolResultContextReducer {
    private static final Set<String> RECEIPT_FIELDS = Set.of(
            "status", "code", "message", "resource", "path", "generation", "kind",
            "authority", "completeness", "returned", "range", "fields", "lineage",
            "next_cursor");

    public ContextProjection reduce(List<ModelMessage> messages, int protectedFromIndex) {
        messages = List.copyOf(messages);
        List<ContextStructure.Unit> units = ContextStructure.units(messages);
        ContextStructure.requireBoundary(units, protectedFromIndex, messages.size());
        ArrayList<ModelMessage> reduced = new ArrayList<>(messages.size());
        boolean changed = false;
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            ModelMessage message = messages.get(messageIndex);
            if (messageIndex >= protectedFromIndex) {
                reduced.add(message);
                continue;
            }
            ArrayList<ModelContent> content = new ArrayList<>(message.content().size());
            boolean messageChanged = false;
            for (ModelContent item : message.content()) {
                if (item instanceof ModelContent.ToolResult result) {
                    String receipt = receipt(
                            result.text(), result.receiptPath(), result.receipts(), result.error());
                    content.add(new ModelContent.ToolResult(
                            result.toolUseId(), receipt, result.receiptPath(), result.receipts(), result.error()));
                    messageChanged |= !receipt.equals(result.text());
                } else {
                    content.add(item);
                }
            }
            reduced.add(messageChanged ? new ModelMessage(message.role(), content) : message);
            changed |= messageChanged;
        }
        return ContextProjection.unestimated(
                reduced,
                changed ? ContextProjection.Kind.TOOL_RESULTS_REDUCED
                        : ContextProjection.Kind.ORIGINAL);
    }

    /** Compatibility entry point for old context fixtures; output is always model text. */
    public ReducedToolResult reduceResult(JsonElement value, boolean originalError) {
        if (value == null) {
            return new ReducedToolResult(new JsonPrimitive(receipt(
                    "status: failure\ncode: context_result_malformed",
                    null,
                    List.of(),
                    true)), true);
        }
        String text = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : new ModelContent.ToolResult("legacy", value, originalError).text();
        return new ReducedToolResult(
                new JsonPrimitive(receipt(text, null, List.of(), originalError)), originalError);
    }

    private static String receipt(
            String text,
            String receiptPath,
            List<ResourceReceipt> receipts,
            boolean error) {
        if (!receipts.isEmpty()) {
            return structuredReceipt(receipts, error);
        }
        return legacyReceipt(text, receiptPath, error);
    }

    private static String structuredReceipt(List<ResourceReceipt> receipts, boolean error) {
        StringBuilder output = new StringBuilder("historical_result: receipt\n")
                .append("status: ").append(error ? "failure" : "retained").append('\n');
        for (int index = 0; index < receipts.size(); index++) {
            ResourceReceipt receipt = receipts.get(index);
            String prefix = receipts.size() == 1 ? "" : "receipt_" + index + '_';
            if (receipt.resultPath() != null) {
                output.append(prefix).append("resource: ").append(receipt.resultPath()).append('\n');
            }
            output.append(prefix).append("generation: ").append(receipt.generationId()).append('\n')
                    .append(prefix).append("kind: ").append(receipt.kind()).append('\n')
                    .append(prefix).append("authority: ").append(receipt.authority()).append('\n')
                    .append(prefix).append("completeness: ").append(receipt.completeness()).append('\n')
                    .append(prefix).append("returned: ").append(receipt.returned());
            if (receipt.total() != null) {
                output.append('/').append(receipt.total());
            }
            output.append('\n');
            if (receipt.fromInclusive() != null) {
                output.append(prefix).append("range: ")
                        .append(receipt.fromInclusive()).append("..").append(receipt.toExclusive()).append('\n');
            }
            if (!receipt.fields().isEmpty()) {
                output.append(prefix).append("fields: ")
                        .append(String.join(",", receipt.fields())).append('\n');
            }
            if (receipt.nextCursor() != null) {
                output.append(prefix).append("next_cursor: ").append(receipt.nextCursor()).append('\n');
            }
        }
        ResourceReceipt first = receipts.getFirst();
        if (first.nextCursor() != null) {
            output.append("next: call resource_read with cursor=").append(first.nextCursor()).append('\n');
        } else if (first.resultPath() != null) {
            output.append("next: call resource_read on ").append(first.resultPath())
                    .append(" with narrower fields when exact live content is needed\n");
        }
        return output.toString().stripTrailing();
    }

    /** Display-only compatibility for pre-VFS history, where no structured receipt exists. */
    private static String legacyReceipt(String text, String receiptPath, boolean error) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : text.lines().toList()) {
            if (line.isBlank() || Character.isWhitespace(line.charAt(0))) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator < 1) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (RECEIPT_FIELDS.contains(key) && !value.isBlank()) {
                fields.putIfAbsent(key, value);
            }
        }
        fields.putIfAbsent("status", error ? "failure" : "retained");
        if (receiptPath != null) {
            fields.put("resource", receiptPath);
        } else {
            fields.putIfAbsent("resource", "unavailable in restored legacy history");
        }
        StringBuilder output = new StringBuilder("historical_result: receipt\n");
        fields.forEach((key, value) -> output.append(key).append(": ").append(value).append('\n'));
        if (receiptPath != null) {
            output.append("next: call resource_read on ").append(receiptPath)
                    .append(" to inspect exact live content\n");
        } else {
            output.append("next: perform a fresh resource lookup if exact content is needed\n");
        }
        return output.toString().stripTrailing();
    }
}
