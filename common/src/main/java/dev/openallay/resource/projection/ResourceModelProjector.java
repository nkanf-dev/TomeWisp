package dev.openallay.resource.projection;

import dev.openallay.agent.tool.ModelToolResultView;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.result.ResourceResultRecord;
import dev.openallay.resource.vfs.ResourceValue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Renders exact Resource truth into budgeted, semantic text for model consumption. */
public final class ResourceModelProjector {
    private static final String CURSOR_PLACEHOLDER = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";

    /**
     * A page boundary selected before a cursor is minted. Boundaries are semantic records,
     * never serialized bytes, so a provider never receives half of a Resource record.
     */
    public record PagePlan(
            ResourceValue projectedTruth,
            long returned,
            long total,
            long fromInclusive,
            long toExclusive,
            boolean hasMore,
            boolean cursorEligible) {
        public PagePlan {
            Objects.requireNonNull(projectedTruth, "projectedTruth");
            if (returned < 0 || total < returned || fromInclusive < 0
                    || toExclusive < fromInclusive || toExclusive - fromInclusive != returned) {
                throw new IllegalArgumentException("Invalid Resource projection page plan");
            }
            if (cursorEligible && (!hasMore || returned == 0)) {
                throw new IllegalArgumentException("Only a progressing partial page may expose a cursor");
            }
        }
    }

    public ModelToolResultView project(ResourceResultRecord record) {
        PagePlan plan = plan(record, 0, 0, Integer.MAX_VALUE, null);
        return project(record, plan, null);
    }

    /**
     * Selects the largest complete prefix whose final semantic projection fits the supplied
     * provider-neutral input budget. One UTF-8 byte is counted as one token, matching the
     * conservative common context estimator.
     */
    public PagePlan plan(
            ResourceResultRecord record,
            long sourceOffset,
            long receiptOffset,
            int projectionTokenBudget,
            Long declaredTotal) {
        Objects.requireNonNull(record, "record");
        if (sourceOffset < 0 || receiptOffset < 0 || projectionTokenBudget < 1) {
            throw new IllegalArgumentException("Invalid Resource projection budget or offset");
        }
        ResourceValue truth = record.node().truth();
        long available = pageCardinality(truth, sourceOffset, Integer.MAX_VALUE);
        long total = declaredTotal == null ? receiptOffset + available : declaredTotal;
        if (total < receiptOffset || total < receiptOffset + available) {
            throw new IllegalArgumentException("Declared Resource total is smaller than the available page");
        }

        int low = 0;
        int high = Math.toIntExact(Math.min(Integer.MAX_VALUE, available));
        while (low < high) {
            int candidate = low + (high - low + 1) / 2;
            ResourceValue candidateTruth = page(truth, sourceOffset, candidate);
            boolean hasMore = receiptOffset + candidate < total;
            String cursor = hasMore && candidate > 0 ? CURSOR_PLACEHOLDER : null;
            String rendered = renderProjection(
                    record,
                    candidateTruth,
                    candidate,
                    total,
                    receiptOffset,
                    cursor,
                    topLevelFields(truth));
            if (utf8Bytes(rendered) <= projectionTokenBudget) {
                low = candidate;
            } else {
                high = candidate - 1;
            }
        }

        ResourceValue projected = page(truth, sourceOffset, low);
        long toExclusive = receiptOffset + low;
        boolean hasMore = toExclusive < total;
        return new PagePlan(projected, low, total, receiptOffset, toExclusive, hasMore, hasMore && low > 0);
    }

    public ModelToolResultView project(ResourceResultRecord record, PagePlan plan, String nextCursor) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(plan, "plan");
        if ((nextCursor != null) != plan.cursorEligible()) {
            throw new IllegalArgumentException("Cursor presence differs from the selected Resource page");
        }
        ResourceReceipt receipt = receipt(record, plan, nextCursor);
        String output = renderProjection(
                record,
                plan.projectedTruth(),
                plan.returned(),
                plan.total(),
                plan.fromInclusive(),
                nextCursor,
                topLevelFields(record.node().truth()));
        return new ModelToolResultView(
                output,
                List.of(receipt),
                semanticUnits(plan.projectedTruth()),
                output.length());
    }

    private static List<String> semanticUnits(ResourceValue projectedTruth) {
        if (projectedTruth instanceof ResourceValue.RecordValue record
                && record.fields().get("items") instanceof ResourceValue.ListValue items) {
            ArrayList<String> units = new ArrayList<>(items.values().size());
            for (int index = 0; index < items.values().size(); index++) {
                StringBuilder unit = new StringBuilder();
                render(items.values().get(index), unit, 0, "item_" + index);
                units.add(unit.toString().stripTrailing());
            }
            return List.copyOf(units);
        }
        StringBuilder unit = new StringBuilder();
        render(projectedTruth, unit, 0, null);
        String value = unit.toString().stripTrailing();
        return value.isBlank() ? List.of("content: available through the exact result resource") : List.of(value);
    }

    private static ResourceReceipt receipt(ResourceResultRecord record, PagePlan plan, String nextCursor) {
        return new ResourceReceipt(
                record.path(),
                record.contentDigest(),
                record.contentKind().name().toLowerCase(),
                plan.returned(),
                plan.total(),
                topLevelFields(record.node().truth()),
                nextCursor,
                record.evidence().authority().name().toLowerCase(),
                record.evidence().completeness().name().toLowerCase(),
                plan.fromInclusive(),
                plan.toExclusive(),
                identities(plan.projectedTruth()));
    }

    private static String renderProjection(
            ResourceResultRecord record,
            ResourceValue projectedTruth,
            long returned,
            long total,
            long receiptOffset,
            String nextCursor,
            List<String> fields) {
        StringBuilder text = new StringBuilder();
        text.append("status: success\n");
        text.append("result: ").append(record.path()).append('\n');
        text.append("kind: ").append(record.contentKind().name().toLowerCase()).append('\n');
        render(projectedTruth, text, 0, null);
        appendEvidence(text, record.evidence());
        text.append("receipt:\n")
                .append("  generation: ").append(record.contentDigest()).append('\n')
                .append("  fields: ").append(String.join(", ", fields)).append('\n')
                .append("  returned: ").append(returned).append('/').append(total).append('\n')
                .append("  range: ").append(receiptOffset).append("..").append(receiptOffset + returned).append('\n');
        if (nextCursor != null) {
            text.append("next_cursor: ").append(nextCursor).append('\n')
                    .append("next: call resource_read with cursor=").append(nextCursor).append('\n');
        } else if (receiptOffset + returned < total) {
            text.append("next: this semantic record exceeds the current budget; call resource_read on ")
                    .append(record.path()).append(" with narrower fields\n");
        } else {
            text.append("next: call resource_read on ").append(record.path())
                    .append(" with narrower fields when exact detail is needed\n");
        }
        return text.toString().stripTrailing();
    }

    private static ResourceValue page(ResourceValue truth, long offset, int limit) {
        if (truth instanceof ResourceValue.RecordValue record
                && record.fields().get("items") instanceof ResourceValue.ListValue items) {
            int start = (int) Math.min(offset, items.values().size());
            int end = Math.min(items.values().size(), start + limit);
            LinkedHashMap<String, ResourceValue> fields = new LinkedHashMap<>(record.fields());
            fields.put("items", new ResourceValue.ListValue(items.values().subList(start, end)));
            return new ResourceValue.RecordValue(fields);
        }
        if (offset > 0 || limit == 0) {
            return new ResourceValue.RecordValue(java.util.Map.of());
        }
        return truth;
    }

    private static long pageCardinality(ResourceValue truth, long offset, int limit) {
        if (truth instanceof ResourceValue.RecordValue record
                && record.fields().get("items") instanceof ResourceValue.ListValue items) {
            long remaining = Math.max(0, items.values().size() - offset);
            return Math.min(limit, remaining);
        }
        return offset == 0 && limit > 0 ? cardinality(truth) : 0;
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void appendEvidence(StringBuilder text, EvidenceMetadata evidence) {
        text.append("evidence:\n")
                .append("  authority: ").append(evidence.authority().name().toLowerCase()).append('\n')
                .append("  completeness: ").append(evidence.completeness().name().toLowerCase()).append('\n')
                .append("  source: ").append(evidence.sourceId()).append('\n')
                .append("  provenance: ").append(evidence.provenance()).append('\n')
                .append("  captured_at: ").append(evidence.capturedAt()).append('\n')
                .append("  game_version: ").append(evidence.gameVersion()).append('\n')
                .append("  loader: ").append(evidence.loader()).append('\n');
    }

    private static void render(ResourceValue value, StringBuilder text, int depth, String name) {
        switch (value) {
            case ResourceValue.Scalar scalar -> line(text, depth, name == null ? "value" : name, scalar(scalar.value()));
            case ResourceValue.RecordValue record -> {
                if (name != null) heading(text, depth, name);
                int childDepth = name == null ? depth : depth + 1;
                record.fields().forEach((field, child) -> render(child, text, childDepth, field));
            }
            case ResourceValue.ListValue list -> {
                if (name != null) heading(text, depth, name);
                int childDepth = name == null ? depth : depth + 1;
                for (int index = 0; index < list.values().size(); index++) {
                    ResourceValue child = list.values().get(index);
                    if (child instanceof ResourceValue.Scalar scalar) {
                        indent(text, childDepth).append("- ").append(scalar(scalar.value())).append('\n');
                    } else {
                        indent(text, childDepth).append("- #").append(index).append('\n');
                        render(child, text, childDepth + 1, null);
                    }
                }
            }
            case ResourceValue.TableValue table -> {
                if (name != null) heading(text, depth, name);
                int childDepth = name == null ? depth : depth + 1;
                indent(text, childDepth).append(String.join(" | ", table.columns())).append('\n');
                for (List<ResourceValue> row : table.rows()) {
                    ArrayList<String> values = new ArrayList<>();
                    row.forEach(cell -> values.add(cell instanceof ResourceValue.Scalar scalar
                            ? scalar(scalar.value()) : "<structured>"));
                    indent(text, childDepth).append(String.join(" | ", values)).append('\n');
                }
            }
            case ResourceValue.DocumentValue document -> {
                if (name != null) heading(text, depth, name);
                int childDepth = name == null ? depth : depth + 1;
                line(text, childDepth, "title", document.title());
                for (ResourceValue.DocumentSection section : document.sections()) {
                    heading(text, childDepth, section.heading() + " [" + section.id() + "]");
                    for (String line : section.text().lines().toList()) {
                        indent(text, childDepth + 1).append(line).append('\n');
                    }
                }
            }
            case ResourceValue.DirectoryValue directory ->
                    line(text, depth, name == null ? "children" : name, Integer.toString(directory.childCount()));
            case ResourceValue.BinaryMetadataValue binary -> {
                if (name != null) heading(text, depth, name);
                int childDepth = name == null ? depth : depth + 1;
                line(text, childDepth, "media_type", binary.mediaType());
                line(text, childDepth, "size", Long.toString(binary.size()));
                line(text, childDepth, "sha256", binary.sha256());
            }
            case ResourceValue.FailureValue failure -> {
                line(text, depth, "code", failure.code());
                line(text, depth, "message", failure.message());
            }
            case ResourceValue.ReferenceValue reference -> {
                line(text, depth, "relation", reference.relation());
                line(text, depth, "target", reference.target().toString());
            }
        }
    }

    private static List<String> topLevelFields(ResourceValue value) {
        return value instanceof ResourceValue.RecordValue record
                ? List.copyOf(record.fields().keySet()) : List.of();
    }

    private static List<String> identities(ResourceValue value) {
        if (!(value instanceof ResourceValue.RecordValue record)
                || !(record.fields().get("items") instanceof ResourceValue.ListValue items)) {
            return List.of();
        }
        ArrayList<String> identities = new ArrayList<>();
        for (ResourceValue item : items.values()) {
            if (item instanceof ResourceValue.RecordValue fields
                    && fields.fields().get("input") instanceof ResourceValue.Scalar scalar
                    && scalar.value() != null) {
                identities.add(scalar.value().toString());
            }
        }
        return List.copyOf(identities);
    }

    private static long cardinality(ResourceValue value) {
        if (value instanceof ResourceValue.RecordValue record
                && record.fields().get("items") instanceof ResourceValue.ListValue items) {
            return items.values().size();
        }
        return switch (value) {
            case ResourceValue.ListValue list -> list.values().size();
            case ResourceValue.TableValue table -> table.rows().size();
            case ResourceValue.DirectoryValue directory -> directory.childCount();
            default -> 1;
        };
    }

    private static String scalar(Object value) {
        if (value == null) return "null";
        return value instanceof BigDecimal number
                ? number.stripTrailingZeros().toPlainString()
                : value.toString().replace('\n', ' ');
    }

    private static void heading(StringBuilder text, int depth, String name) {
        indent(text, depth).append(name).append(':').append('\n');
    }

    private static void line(StringBuilder text, int depth, String name, String value) {
        indent(text, depth).append(name).append(": ").append(value).append('\n');
    }

    private static StringBuilder indent(StringBuilder text, int depth) {
        return text.append("  ".repeat(Math.max(0, depth)));
    }
}
