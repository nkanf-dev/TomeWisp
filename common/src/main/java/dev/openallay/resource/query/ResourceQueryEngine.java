package dev.openallay.resource.query;

import dev.openallay.resource.vfs.ResourceFileSystem;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceLink;
import dev.openallay.resource.vfs.ResourceNode;
import dev.openallay.resource.vfs.ResourceOperationException;
import dev.openallay.resource.vfs.ResourceOperationFailure;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.resource.vfs.ResourceView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Executes closed typed query plans over immutable VFS values. */
public final class ResourceQueryEngine {
    public record Row(ResourcePath source, int ordinal, Map<String, ResourceValue> fields) {
        public Row {
            Objects.requireNonNull(source, "source");
            if (ordinal < 0) throw new IllegalArgumentException("ordinal must be non-negative");
            fields = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(fields, "fields")));
        }
    }

    public record StageCardinality(int index, String operation, int inputRows, int outputRows) {
        public StageCardinality {
            if (index < 0 || inputRows < 0 || outputRows < 0) {
                throw new IllegalArgumentException("Invalid stage cardinality");
            }
        }
    }

    public record Result(
            List<Row> rows,
            ResourceFieldSchema sourceSchema,
            List<StageCardinality> stages,
            int sourceRows) {
        public Result {
            rows = List.copyOf(rows);
            Objects.requireNonNull(sourceSchema, "sourceSchema");
            stages = List.copyOf(stages);
        }
    }

    public record BatchResult(int inputIndex, Result value, ResourceOperationFailure failure) {
        public BatchResult {
            if (inputIndex < 0) throw new IllegalArgumentException("inputIndex must be non-negative");
            if ((value == null) == (failure == null)) {
                throw new IllegalArgumentException("Exactly one of value and failure is required");
            }
        }
    }

    public List<BatchResult> execute(ResourceView view, List<ResourceQueryPlan> plans) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(plans, "plans");
        ArrayList<BatchResult> results = new ArrayList<>(plans.size());
        for (int index = 0; index < plans.size(); index++) {
            ResourceQueryPlan plan = plans.get(index);
            try {
                results.add(new BatchResult(index, execute(view, plan), null));
            } catch (RuntimeException exception) {
                ResourcePath root = plan.roots().getFirst();
                String code = exception instanceof ResourceQueryException queryFailure
                        ? queryFailure.code()
                        : exception instanceof ResourceOperationException operationFailure
                        ? operationFailure.code() : "invalid_resource_query";
                results.add(new BatchResult(index, null,
                        new ResourceOperationFailure(code, root, field(exception), message(exception))));
            }
        }
        return List.copyOf(results);
    }

    public Result execute(ResourceView view, ResourceQueryPlan plan) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(plan, "plan");
        List<Row> rows = source(view, plan.roots());
        int sourceRows = rows.size();
        ResourceFieldSchema schema = ResourceFieldSchema.discover(rows.stream().map(Row::fields).toList());
        ArrayList<StageCardinality> cardinalities = new ArrayList<>();
        for (int index = 0; index < plan.stages().size(); index++) {
            ensureActive(view);
            ResourceQueryStage stage = plan.stages().get(index);
            int before = rows.size();
            rows = apply(view, rows, schema, stage);
            cardinalities.add(new StageCardinality(index, stageName(stage), before, rows.size()));
            schema = ResourceFieldSchema.discover(rows.stream().map(Row::fields).toList());
        }
        return new Result(rows, ResourceFieldSchema.discover(source(view, plan.roots()).stream()
                .map(Row::fields).toList()), cardinalities, sourceRows);
    }

    public ResourceFieldSchema describe(ResourceView view, List<ResourcePath> roots) {
        return ResourceFieldSchema.discover(source(view, roots).stream().map(Row::fields).toList());
    }

    private static List<Row> apply(
            ResourceView view, List<Row> rows, ResourceFieldSchema schema, ResourceQueryStage stage) {
        return switch (stage) {
            case ResourceQueryStage.Search search -> search(rows, schema, search);
            case ResourceQueryStage.Filter filter -> filter(rows, schema, filter);
            case ResourceQueryStage.Select select -> select(rows, schema, select);
            case ResourceQueryStage.Sort sort -> sort(rows, schema, sort);
            case ResourceQueryStage.Group group -> group(rows, schema, group);
            case ResourceQueryStage.Aggregate aggregate -> aggregate(rows, schema, aggregate);
            case ResourceQueryStage.Expand expand -> expand(rows, schema, expand);
            case ResourceQueryStage.Take take -> take(rows, take);
            case ResourceQueryStage.Follow follow -> follow(view, rows, follow);
        };
    }

    private static List<Row> search(
            List<Row> rows, ResourceFieldSchema schema, ResourceQueryStage.Search stage) {
        if (stage.field() != null) schema.require(stage.field(), ResourceFieldSchema.Operation.SEARCH);
        String query = canonical(stage.query());
        return rows.stream().filter(row -> {
            if (stage.field() != null) {
                return values(row, stage.field()).stream().anyMatch(value -> text(value).contains(query));
            }
            return row.fields().values().stream().flatMap(value -> primitiveValues(value).stream())
                    .anyMatch(value -> text(value).contains(query));
        }).toList();
    }

    private static List<Row> filter(
            List<Row> rows, ResourceFieldSchema schema, ResourceQueryStage.Filter stage) {
        schema.require(stage.field(), stage.operator() == ResourceQueryStage.Operator.EXISTS
                ? ResourceFieldSchema.Operation.SELECT : ResourceFieldSchema.Operation.FILTER);
        return rows.stream().filter(row -> {
            List<ResourceValue> found = values(row, stage.field());
            if (stage.operator() == ResourceQueryStage.Operator.EXISTS) {
                return found.stream().anyMatch(ResourceQueryEngine::present);
            }
            return found.stream().anyMatch(actual -> compare(actual, stage.operator(), stage.value()));
        }).toList();
    }

    private static List<Row> select(
            List<Row> rows, ResourceFieldSchema schema, ResourceQueryStage.Select stage) {
        stage.fields().forEach(field -> schema.require(field, ResourceFieldSchema.Operation.SELECT));
        return rows.stream().map(row -> {
            LinkedHashMap<String, ResourceValue> selected = new LinkedHashMap<>();
            for (String field : stage.fields()) {
                List<ResourceValue> values = values(row, field);
                if (values.size() == 1) selected.put(field, values.getFirst());
                else if (!values.isEmpty()) selected.put(field, new ResourceValue.ListValue(values));
            }
            return new Row(row.source(), row.ordinal(), selected);
        }).toList();
    }

    private static List<Row> sort(
            List<Row> rows, ResourceFieldSchema schema, ResourceQueryStage.Sort stage) {
        schema.require(stage.field(), ResourceFieldSchema.Operation.SORT);
        rows.forEach(row -> single(row, stage.field(), "SORT"));
        Comparator<Row> comparator = (left, right) -> {
            try {
                return compareValues(single(left, stage.field(), "SORT"), single(right, stage.field(), "SORT"));
            } catch (ResourceQueryException failure) {
                throw new ResourceQueryException(failure.code(), stage.field(), failure.getMessage());
            }
        };
        if (stage.direction() == ResourceQueryStage.Direction.DESC) comparator = comparator.reversed();
        return rows.stream().sorted(comparator.thenComparing(Row::source).thenComparingInt(Row::ordinal)).toList();
    }

    private static List<Row> group(
            List<Row> rows, ResourceFieldSchema schema, ResourceQueryStage.Group stage) {
        schema.require(stage.field(), ResourceFieldSchema.Operation.GROUP);
        TreeMap<String, GroupBucket> groups = new TreeMap<>();
        for (Row row : rows) {
            ResourceValue value = single(row, stage.field(), "GROUP");
            groups.computeIfAbsent(text(value), ignored -> new GroupBucket(value, row.source())).count++;
        }
        ArrayList<Row> grouped = new ArrayList<>();
        int ordinal = 0;
        for (GroupBucket bucket : groups.values()) {
            grouped.add(new Row(bucket.source, ordinal++, Map.of(
                    stage.field(), bucket.value,
                    "/count", ResourceValue.Scalar.number(bucket.count))));
        }
        return List.copyOf(grouped);
    }

    private static List<Row> aggregate(
            List<Row> rows, ResourceFieldSchema schema, ResourceQueryStage.Aggregate stage) {
        if (stage.function() != ResourceQueryStage.AggregateFunction.COUNT) {
            schema.require(stage.field(), ResourceFieldSchema.Operation.AGGREGATE);
        }
        if (stage.groupBy() != null) schema.require(stage.groupBy(), ResourceFieldSchema.Operation.GROUP);
        if (rows.isEmpty()) return List.of();
        TreeMap<String, List<Row>> groups = new TreeMap<>();
        if (stage.groupBy() == null) groups.put("", rows);
        else for (Row row : rows) {
            ResourceValue group = single(row, stage.groupBy(), "AGGREGATE groupBy");
            groups.computeIfAbsent(text(group), ignored -> new ArrayList<>()).add(row);
        }
        ArrayList<Row> result = new ArrayList<>();
        int ordinal = 0;
        for (Map.Entry<String, List<Row>> entry : groups.entrySet()) {
            LinkedHashMap<String, ResourceValue> fields = new LinkedHashMap<>();
            if (stage.groupBy() != null) fields.put(stage.groupBy(), new ResourceValue.Scalar(entry.getKey()));
            fields.put("/aggregate", new ResourceValue.Scalar(stage.function().name().toLowerCase(Locale.ROOT)));
            fields.put("/value", aggregateValue(entry.getValue(), stage));
            ResourcePath source = entry.getValue().isEmpty() ? rows.getFirst().source() : entry.getValue().getFirst().source();
            result.add(new Row(source, ordinal++, fields));
        }
        return List.copyOf(result);
    }

    private static ResourceValue aggregateValue(List<Row> rows, ResourceQueryStage.Aggregate stage) {
        if (stage.function() == ResourceQueryStage.AggregateFunction.COUNT) {
            return ResourceValue.Scalar.number(rows.size());
        }
        List<BigDecimal> numbers = rows.stream()
                .map(row -> single(row, stage.field(), "AGGREGATE"))
                .filter(ResourceQueryEngine::present)
                .map(ResourceQueryEngine::number)
                .toList();
        if (numbers.isEmpty()) return new ResourceValue.Scalar(null);
        BigDecimal value = switch (stage.function()) {
            case MIN -> numbers.stream().min(BigDecimal::compareTo).orElseThrow();
            case MAX -> numbers.stream().max(BigDecimal::compareTo).orElseThrow();
            case SUM -> numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case AVG -> numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(numbers.size()), 8, RoundingMode.HALF_UP).stripTrailingZeros();
            case COUNT -> throw new IllegalStateException();
        };
        return new ResourceValue.Scalar(value);
    }

    private static List<Row> expand(
            List<Row> rows, ResourceFieldSchema schema, ResourceQueryStage.Expand stage) {
        schema.require(stage.field(), ResourceFieldSchema.Operation.EXPAND);
        ArrayList<Row> result = new ArrayList<>();
        int ordinal = 0;
        for (Row row : rows) {
            ResourceValue value = exactlyOne(row, stage.field(), "EXPAND");
            if (!(value instanceof ResourceValue.ListValue list)) {
                throw new ResourceQueryException("type_mismatch", stage.field(),
                        "EXPAND requires a list field: " + stage.field());
            }
            for (ResourceValue element : list.values()) {
                LinkedHashMap<String, ResourceValue> fields = new LinkedHashMap<>(row.fields());
                fields.put(stage.field(), element);
                result.add(new Row(row.source(), ordinal++, fields));
            }
        }
        return List.copyOf(result);
    }

    private static List<Row> take(List<Row> rows, ResourceQueryStage.Take stage) {
        return List.copyOf(rows.subList(0, Math.min(stage.count(), rows.size())));
    }

    private static List<Row> follow(ResourceView view, List<Row> rows, ResourceQueryStage.Follow stage) {
        ArrayList<Row> result = new ArrayList<>();
        LinkedHashSet<TraversalKey> visited = new LinkedHashSet<>();
        int ordinal = 0;
        for (Row row : rows) {
            ArrayDeque<Traversal> pending = new ArrayDeque<>();
            pending.add(new Traversal(row.source(), 0));
            while (!pending.isEmpty()) {
                ensureActive(view);
                Traversal current = pending.removeFirst();
                if (current.depth >= stage.maxDepth()) continue;
                ResourceNode node = view.require(current.path);
                String generation = view.generation(current.path.mount()).id();
                TraversalKey key = new TraversalKey(current.path, stage.relation(), generation);
                if (!visited.add(key)) continue;
                for (ResourceLink link : node.links()) {
                    if (!link.relation().equals(stage.relation())) continue;
                    ResourceNode target = view.require(link.target());
                    for (Row targetRow : rows(target)) {
                        result.add(new Row(targetRow.source(), ordinal++, targetRow.fields()));
                    }
                    pending.addLast(new Traversal(link.target(), current.depth + 1));
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<Row> source(ResourceView view, List<ResourcePath> roots) {
        LinkedHashMap<RowIdentity, Row> unique = new LinkedHashMap<>();
        int ordinal = 0;
        for (ResourcePath root : roots.stream().distinct().sorted().toList()) {
            ResourceNode rootNode = view.require(root);
            List<ResourceNode> nodes;
            if (rootNode.kind() == ResourceKind.DIRECTORY) {
                nodes = view.generation(root.mount()).nodes().values().stream()
                        .filter(node -> node.path().startsWith(root))
                        .filter(node -> node.kind() != ResourceKind.DIRECTORY)
                        .filter(node -> !node.path().segments().getLast().startsWith("@"))
                        .sorted(Comparator.comparing(ResourceNode::path))
                        .toList();
            } else {
                nodes = List.of(rootNode);
            }
            for (ResourceNode node : nodes) {
                ensureActive(view);
                for (Row row : rows(node)) {
                    RowIdentity identity = new RowIdentity(row.source(), row.ordinal());
                    if (!unique.containsKey(identity)) {
                        unique.put(identity, new Row(row.source(), ordinal++, row.fields()));
                    }
                }
            }
        }
        return List.copyOf(unique.values());
    }

    private static List<Row> rows(ResourceNode node) {
        return switch (node.truth()) {
            case ResourceValue.RecordValue record -> List.of(new Row(node.path(), 0, withPath(node.path(), record.fields())));
            case ResourceValue.TableValue table -> {
                ArrayList<Row> rows = new ArrayList<>();
                for (int row = 0; row < table.rows().size(); row++) {
                    LinkedHashMap<String, ResourceValue> fields = new LinkedHashMap<>();
                    fields.put("/@path", new ResourceValue.Scalar(node.path().toString()));
                    for (int column = 0; column < table.columns().size(); column++) {
                        fields.put("/" + escape(table.columns().get(column)), table.rows().get(row).get(column));
                    }
                    rows.add(new Row(node.path(), row, fields));
                }
                yield List.copyOf(rows);
            }
            case ResourceValue.ListValue list -> {
                ArrayList<Row> rows = new ArrayList<>();
                for (int index = 0; index < list.values().size(); index++) {
                    ResourceValue value = list.values().get(index);
                    Map<String, ResourceValue> fields = value instanceof ResourceValue.RecordValue record
                            ? withPath(node.path(), record.fields())
                            : Map.of("/@path", new ResourceValue.Scalar(node.path().toString()), "/value", value);
                    rows.add(new Row(node.path(), index, fields));
                }
                yield List.copyOf(rows);
            }
            default -> List.of(new Row(node.path(), 0, Map.of(
                    "/@path", new ResourceValue.Scalar(node.path().toString()), "/value", node.truth())));
        };
    }

    private static Map<String, ResourceValue> withPath(ResourcePath path, Map<String, ResourceValue> fields) {
        LinkedHashMap<String, ResourceValue> result = new LinkedHashMap<>();
        result.put("/@path", new ResourceValue.Scalar(path.toString()));
        fields.forEach((key, value) -> result.put("/" + escape(key), value));
        return result;
    }

    private static List<ResourceValue> values(Row row, String path) {
        ResourceValue direct = row.fields().get(path);
        if (direct != null) return List.of(direct);
        String[] parts = path.substring(1).split("/", -1);
        if (parts.length == 0) return List.of();
        ResourceValue root = row.fields().get("/" + parts[0]);
        if (root == null) return List.of();
        List<ResourceValue> current = List.of(root);
        for (int index = 1; index < parts.length; index++) {
            String part = unescape(parts[index]);
            ArrayList<ResourceValue> next = new ArrayList<>();
            for (ResourceValue value : current) {
                if (part.equals("*") && value instanceof ResourceValue.ListValue list) next.addAll(list.values());
                else if (value instanceof ResourceValue.RecordValue record) {
                    ResourceValue child = record.fields().get(part);
                    if (child != null) next.add(child);
                }
            }
            current = List.copyOf(next);
        }
        return current;
    }

    private static ResourceValue single(Row row, String field, String operation) {
        ResourceValue value = exactlyOne(row, field, operation);
        if (!(value instanceof ResourceValue.Scalar)) {
            throw new ResourceQueryException("type_mismatch", field,
                    operation + " requires one scalar value for field '" + field + "'");
        }
        return value;
    }

    private static ResourceValue exactlyOne(Row row, String field, String operation) {
        List<ResourceValue> values = values(row, field);
        if (values.size() != 1) {
            throw new ResourceQueryException("type_mismatch", field,
                    operation + " requires exactly one value for field '" + field + "'");
        }
        return values.getFirst();
    }

    private static boolean compare(
            ResourceValue actual, ResourceQueryStage.Operator operator, ResourceValue.Scalar expected) {
        if (!present(actual) || !(actual instanceof ResourceValue.Scalar scalar)) return false;
        return switch (operator) {
            case EQ -> compareValues(scalar, expected) == 0;
            case NE -> compareValues(scalar, expected) != 0;
            case CONTAINS -> text(scalar).contains(canonical(Objects.toString(expected.value(), "")));
            case GT -> number(scalar).compareTo(number(expected)) > 0;
            case GTE -> number(scalar).compareTo(number(expected)) >= 0;
            case LT -> number(scalar).compareTo(number(expected)) < 0;
            case LTE -> number(scalar).compareTo(number(expected)) <= 0;
            case EXISTS -> throw new IllegalStateException();
        };
    }

    private static int compareValues(ResourceValue left, ResourceValue right) {
        if (!(left instanceof ResourceValue.Scalar leftScalar)
                || !(right instanceof ResourceValue.Scalar rightScalar)) {
            throw new ResourceQueryException("type_mismatch", "Comparison requires scalar values");
        }
        if (leftScalar.value() == null) return rightScalar.value() == null ? 0 : 1;
        if (rightScalar.value() == null) return -1;
        if (leftScalar.value() instanceof BigDecimal leftNumber
                && rightScalar.value() instanceof BigDecimal rightNumber) {
            return leftNumber.compareTo(rightNumber);
        }
        if (!leftScalar.value().getClass().equals(rightScalar.value().getClass())) {
            throw new ResourceQueryException("mixed_field_types", "Cannot compare mixed scalar types");
        }
        return canonical(leftScalar.value().toString()).compareTo(canonical(rightScalar.value().toString()));
    }

    private static BigDecimal number(ResourceValue value) {
        if (value instanceof ResourceValue.Scalar scalar && scalar.value() instanceof BigDecimal number) return number;
        throw new ResourceQueryException("type_mismatch", "Numeric operation requires number values");
    }

    private static boolean present(ResourceValue value) {
        return !(value instanceof ResourceValue.Scalar scalar) || scalar.value() != null;
    }

    private static List<ResourceValue> primitiveValues(ResourceValue value) {
        ArrayList<ResourceValue> result = new ArrayList<>();
        switch (value) {
            case ResourceValue.Scalar scalar -> result.add(scalar);
            case ResourceValue.RecordValue record -> record.fields().values().forEach(child -> result.addAll(primitiveValues(child)));
            case ResourceValue.ListValue list -> list.values().forEach(child -> result.addAll(primitiveValues(child)));
            case ResourceValue.TableValue table -> table.rows().forEach(row ->
                    row.forEach(child -> result.addAll(primitiveValues(child))));
            case ResourceValue.DocumentValue document -> {
                result.add(new ResourceValue.Scalar(document.title()));
                document.sections().forEach(section -> {
                    result.add(new ResourceValue.Scalar(section.heading()));
                    result.add(new ResourceValue.Scalar(section.text()));
                });
            }
            case ResourceValue.FailureValue failure -> {
                result.add(new ResourceValue.Scalar(failure.code()));
                result.add(new ResourceValue.Scalar(failure.message()));
            }
            case ResourceValue.ReferenceValue reference -> {
                result.add(new ResourceValue.Scalar(reference.target().toString()));
                result.add(new ResourceValue.Scalar(reference.relation()));
            }
            case ResourceValue.BinaryMetadataValue binary -> {
                result.add(new ResourceValue.Scalar(binary.mediaType()));
                result.add(new ResourceValue.Scalar(binary.sha256()));
            }
            default -> { }
        }
        return List.copyOf(result);
    }

    private static String text(ResourceValue value) {
        if (!(value instanceof ResourceValue.Scalar scalar) || scalar.value() == null) return "";
        return canonical(scalar.value() instanceof BigDecimal number
                ? number.stripTrailingZeros().toPlainString() : scalar.value().toString());
    }

    private static String canonical(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String stageName(ResourceQueryStage stage) {
        return switch (stage) {
            case ResourceQueryStage.Search ignored -> "SEARCH";
            case ResourceQueryStage.Filter ignored -> "FILTER";
            case ResourceQueryStage.Select ignored -> "SELECT";
            case ResourceQueryStage.Sort ignored -> "SORT";
            case ResourceQueryStage.Group ignored -> "GROUP";
            case ResourceQueryStage.Aggregate ignored -> "AGGREGATE";
            case ResourceQueryStage.Expand ignored -> "EXPAND";
            case ResourceQueryStage.Take ignored -> "TAKE";
            case ResourceQueryStage.Follow ignored -> "FOLLOW";
        };
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String unescape(String value) {
        return value.replace("~1", "/").replace("~0", "~");
    }

    private static void ensureActive(ResourceView view) {
        if (view.scope().cancelled().getAsBoolean()) {
            throw new ResourceOperationException("agent_cancelled", "Resource query was cancelled");
        }
    }

    private static String field(RuntimeException exception) {
        return exception instanceof ResourceQueryException queryFailure ? queryFailure.field() : null;
    }

    private static String message(RuntimeException exception) {
        return exception.getMessage() == null ? "Resource query failed" : exception.getMessage();
    }

    private static final class GroupBucket {
        private final ResourceValue value;
        private final ResourcePath source;
        private int count;

        private GroupBucket(ResourceValue value, ResourcePath source) {
            this.value = value;
            this.source = source;
        }
    }

    private record Traversal(ResourcePath path, int depth) { }
    private record TraversalKey(ResourcePath path, String relation, String generation) { }
    private record RowIdentity(ResourcePath path, int sourceOrdinal) { }
}
