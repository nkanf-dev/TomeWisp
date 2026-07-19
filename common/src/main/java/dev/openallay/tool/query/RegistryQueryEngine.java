package dev.openallay.tool.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.openallay.context.RegistryEntrySnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Executes a closed relational pipeline over detached, typed registry data. */
public final class RegistryQueryEngine {
    public enum Dataset { all, items, blocks, effects, potions, entities, attributes }

    public record Stage(int index, QueryOperation.Op operation, int inputRows, int outputRows) {}

    /** A runtime-discovered JSON Pointer; no Minecraft domain field is built into this contract. */
    public record Field(
            String path,
            List<String> types,
            int presentRows,
            int totalRows,
            String example,
            List<String> operations) {
        public Field {
            types = List.copyOf(types);
            operations = List.copyOf(operations);
        }
    }

    public record Schema(Dataset dataset, String namespace, int rows, List<Field> fields) {
        public Schema {
            namespace = namespace == null ? "" : namespace;
            fields = List.copyOf(fields);
        }
    }

    public record Result(
            Dataset dataset,
            List<String> columns,
            List<Map<String, JsonElement>> rows,
            List<Stage> stages,
            int sourceRows) {
        public Result {
            columns = List.copyOf(columns);
            rows = rows.stream().map(RegistryQueryEngine::copyRow).toList();
            stages = List.copyOf(stages);
        }

        @Override
        public List<Map<String, JsonElement>> rows() {
            return rows.stream().map(RegistryQueryEngine::copyRow).toList();
        }
    }

    public Schema describe(
            Collection<RegistryEntrySnapshot> entries, Dataset dataset, String namespace) {
        List<JsonObject> rows = source(entries, dataset, namespace);
        Map<String, FieldAccumulator> fields = new TreeMap<>();
        for (int index = 0; index < rows.size(); index++) {
            walk(rows.get(index), "", index, fields);
        }
        List<Field> discovered = fields.entrySet().stream()
                .filter(entry -> !entry.getKey().isEmpty())
                .map(entry -> entry.getValue().field(entry.getKey(), rows.size()))
                .toList();
        return new Schema(dataset, namespace, rows.size(), discovered);
    }

    public Result execute(
            Collection<RegistryEntrySnapshot> entries,
            Dataset dataset,
            String namespace,
            List<QueryOperation> operations) {
        if (dataset == null || operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("dataset and at least one pipeline stage are required");
        }
        List<JsonObject> rows = source(entries, dataset, namespace);
        int sourceRows = rows.size();
        List<Stage> stages = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            QueryOperation operation = operations.get(index);
            if (operation == null || operation.op() == null) {
                throw new IllegalArgumentException("pipeline stage " + index + " has no operation");
            }
            int before = rows.size();
            rows = apply(rows, operation, index);
            stages.add(new Stage(index, operation.op(), before, rows.size()));
        }
        List<Map<String, JsonElement>> resultRows = rows.stream()
                .map(RegistryQueryEngine::toMap)
                .toList();
        List<String> columns = resultRows.stream()
                .flatMap(row -> row.keySet().stream())
                .distinct()
                .sorted()
                .toList();
        return new Result(dataset, columns, resultRows, stages, sourceRows);
    }

    public Result execute(
            Collection<RegistryEntrySnapshot> entries,
            Dataset dataset,
            List<QueryOperation> operations) {
        return execute(entries, dataset, null, operations);
    }

    private static List<JsonObject> source(
            Collection<RegistryEntrySnapshot> entries, Dataset dataset, String namespace) {
        String selectedNamespace = namespace == null ? "" : namespace.strip();
        return entries.stream()
                .filter(entry -> matchesDataset(entry, dataset))
                .filter(entry -> selectedNamespace.isEmpty() || entry.namespace().equals(selectedNamespace))
                .map(RegistryQueryEngine::row)
                .toList();
    }

    private static List<JsonObject> apply(
            List<JsonObject> rows, QueryOperation operation, int index) {
        return switch (operation.op()) {
            case SEARCH -> search(rows, required(operation.value(), index, "value"), operation.field());
            case FILTER -> filter(rows, required(operation.field(), index, "field"),
                    required(operation.operator(), index, "operator"), operation.value());
            case SELECT -> select(rows, requiredFields(operation.fields(), index));
            case SORT -> sort(rows, required(operation.field(), index, "field"),
                    operation.direction() == null ? QueryOperation.Direction.ASC : operation.direction());
            case GROUP -> group(rows, required(operation.field(), index, "field"));
            case AGGREGATE -> aggregate(rows, operation.field(),
                    required(operation.aggregate(), index, "aggregate"), operation.groupBy());
            case EXPAND -> expand(rows, required(operation.field(), index, "field"));
            case TAKE -> take(rows, requiredCount(operation.count(), index));
        };
    }

    private static List<JsonObject> search(List<JsonObject> rows, String value, String field) {
        String needle = canonical(value);
        requireKnownField(rows, field);
        return rows.stream().filter(row -> {
            List<JsonElement> values = field == null ? primitives(row) : values(row, field);
            return values.stream().map(RegistryQueryEngine::text)
                    .anyMatch(candidate -> candidate.contains(needle));
        }).toList();
    }

    private static List<JsonObject> filter(
            List<JsonObject> rows,
            String field,
            QueryOperation.Operator operator,
            String value) {
        requireKnownField(rows, field);
        if (operator != QueryOperation.Operator.EXISTS && (value == null || value.isBlank())) {
            throw new IllegalArgumentException("FILTER value is required unless operator is EXISTS");
        }
        return rows.stream().filter(row -> {
            List<JsonElement> values = values(row, field);
            if (operator == QueryOperation.Operator.EXISTS) {
                return values.stream().anyMatch(RegistryQueryEngine::present);
            }
            return values.stream().anyMatch(actual -> compare(actual, operator, value));
        }).toList();
    }

    private static boolean compare(JsonElement actual, QueryOperation.Operator operator, String expected) {
        if (!present(actual) || !actual.isJsonPrimitive()) return false;
        JsonPrimitive primitive = actual.getAsJsonPrimitive();
        return switch (operator) {
            case EQ -> canonical(primitive.getAsString()).equals(canonical(expected));
            case NE -> !canonical(primitive.getAsString()).equals(canonical(expected));
            case CONTAINS -> canonical(primitive.getAsString()).contains(canonical(expected));
            case GT -> numeric(primitive).compareTo(numeric(expected)) > 0;
            case GTE -> numeric(primitive).compareTo(numeric(expected)) >= 0;
            case LT -> numeric(primitive).compareTo(numeric(expected)) < 0;
            case LTE -> numeric(primitive).compareTo(numeric(expected)) <= 0;
            case EXISTS -> throw new IllegalStateException();
        };
    }

    private static List<JsonObject> select(List<JsonObject> rows, List<String> fields) {
        fields.forEach(field -> requireKnownField(rows, field));
        return rows.stream().map(row -> {
            JsonObject selected = new JsonObject();
            fields.forEach(field -> {
                List<JsonElement> found = values(row, field);
                if (found.isEmpty()) return;
                if (found.size() == 1) selected.add(field, found.getFirst().deepCopy());
                else {
                    JsonArray array = new JsonArray();
                    found.forEach(value -> array.add(value.deepCopy()));
                    selected.add(field, array);
                }
            });
            return selected;
        }).toList();
    }

    private static List<JsonObject> sort(
            List<JsonObject> rows, String field, QueryOperation.Direction direction) {
        requireKnownField(rows, field);
        rows.forEach(row -> requireSingleScalar(row, field, "SORT"));
        Comparator<JsonObject> comparator = (left, right) -> compareValues(
                single(left, field), single(right, field));
        if (direction == QueryOperation.Direction.DESC) comparator = comparator.reversed();
        return rows.stream().sorted(comparator
                .thenComparing(row -> stringAt(row, "/id"))
                .thenComparing(row -> stringAt(row, "/kind"))).toList();
    }

    private static List<JsonObject> group(List<JsonObject> rows, String field) {
        requireKnownField(rows, field);
        rows.forEach(row -> requireSingleScalar(row, field, "GROUP"));
        Map<String, Long> counts = new TreeMap<>();
        rows.forEach(row -> counts.merge(text(single(row, field)), 1L, Long::sum));
        return counts.entrySet().stream().map(entry -> {
            JsonObject result = new JsonObject();
            result.addProperty(field, entry.getKey());
            result.addProperty("count", entry.getValue());
            return result;
        }).toList();
    }

    private static List<JsonObject> aggregate(
            List<JsonObject> rows,
            String field,
            QueryOperation.Aggregate aggregate,
            String groupBy) {
        if (aggregate != QueryOperation.Aggregate.COUNT) {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("AGGREGATE field is required unless aggregate is COUNT");
            }
            requireKnownField(rows, field);
            rows.forEach(row -> requireSingleScalar(row, field, "AGGREGATE"));
        }
        requireKnownField(rows, groupBy);
        if (groupBy != null) rows.forEach(row -> requireSingleScalar(row, groupBy, "AGGREGATE groupBy"));
        Map<String, List<JsonObject>> groups = new TreeMap<>();
        if (groupBy == null) groups.put("", rows);
        else rows.forEach(row -> groups.computeIfAbsent(text(single(row, groupBy)), ignored -> new ArrayList<>())
                .add(row));
        return groups.entrySet().stream().map(group -> {
            JsonObject result = new JsonObject();
            if (groupBy != null) result.addProperty(groupBy, group.getKey());
            result.addProperty("aggregate", aggregate.name().toLowerCase(Locale.ROOT));
            JsonElement value = aggregateValue(group.getValue(), field, aggregate);
            result.add("value", value);
            return result;
        }).toList();
    }

    private static JsonElement aggregateValue(
            List<JsonObject> rows, String field, QueryOperation.Aggregate aggregate) {
        if (aggregate == QueryOperation.Aggregate.COUNT) return new JsonPrimitive(rows.size());
        List<BigDecimal> values = rows.stream()
                .map(row -> single(row, field))
                .filter(RegistryQueryEngine::present)
                .map(RegistryQueryEngine::numeric)
                .toList();
        if (values.isEmpty()) return JsonNull.INSTANCE;
        BigDecimal value = switch (aggregate) {
            case MIN -> values.stream().min(BigDecimal::compareTo).orElseThrow();
            case MAX -> values.stream().max(BigDecimal::compareTo).orElseThrow();
            case SUM -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case AVG -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
            case COUNT -> throw new IllegalStateException();
        };
        return new JsonPrimitive(value.stripTrailingZeros());
    }

    private static List<JsonObject> expand(List<JsonObject> rows, String field) {
        requireKnownField(rows, field);
        List<JsonObject> result = new ArrayList<>();
        for (JsonObject row : rows) {
            JsonElement selected = direct(row, field);
            if (selected == null || !selected.isJsonArray()) {
                throw new IllegalArgumentException("EXPAND requires an array field: " + field);
            }
            for (JsonElement element : selected.getAsJsonArray()) {
                JsonObject expanded = row.deepCopy();
                replace(expanded, field, element.deepCopy());
                result.add(expanded);
            }
        }
        return List.copyOf(result);
    }

    private static List<JsonObject> take(List<JsonObject> rows, int count) {
        return List.copyOf(rows.subList(0, Math.min(rows.size(), count)));
    }

    private static JsonObject row(RegistryEntrySnapshot entry) {
        JsonObject row = new JsonObject();
        row.addProperty("id", entry.id());
        row.addProperty("kind", entry.kind());
        row.addProperty("displayName", entry.displayName());
        row.addProperty("namespace", entry.namespace());
        row.addProperty("provenance", entry.provenance());
        row.add("aliases", array(entry.aliases()));
        row.add("tags", array(entry.tags()));
        row.add("components", array(entry.components()));
        JsonObject data = new JsonObject();
        entry.properties().forEach((key, value) -> data.add(key, value.deepCopy()));
        row.add("data", data);
        return row;
    }

    private static JsonArray array(Collection<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static boolean matchesDataset(RegistryEntrySnapshot entry, Dataset dataset) {
        if (dataset == Dataset.all) return true;
        String singular = switch (dataset) {
            case items -> "item";
            case blocks -> "block";
            case effects -> "effect";
            case potions -> "potion";
            case entities -> "entity";
            case attributes -> "attribute";
            case all -> throw new IllegalStateException();
        };
        return entry.kind().equals(singular);
    }

    private static void requireKnownField(List<JsonObject> rows, String field) {
        if (field == null || rows.isEmpty()) return;
        if (rows.stream().noneMatch(row -> !values(row, field).isEmpty())) {
            List<String> available = new RegistryQueryEngine().describeRows(rows).fields().stream()
                    .map(Field::path).toList();
            throw new IllegalArgumentException(
                    "unknown field '" + field + "'; use describe=true; available fields: " + available);
        }
    }

    private Schema describeRows(List<JsonObject> rows) {
        Map<String, FieldAccumulator> fields = new TreeMap<>();
        for (int index = 0; index < rows.size(); index++) walk(rows.get(index), "", index, fields);
        return new Schema(Dataset.all, "", rows.size(), fields.entrySet().stream()
                .filter(entry -> !entry.getKey().isEmpty())
                .map(entry -> entry.getValue().field(entry.getKey(), rows.size()))
                .toList());
    }

    private static void walk(
            JsonElement value, String path, int row, Map<String, FieldAccumulator> fields) {
        if (!path.isEmpty()) fields.computeIfAbsent(path, ignored -> new FieldAccumulator()).add(value, row);
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return;
        if (value.isJsonObject()) {
            value.getAsJsonObject().entrySet().forEach(entry ->
                    walk(entry.getValue(), path + "/" + escape(entry.getKey()), row, fields));
            return;
        }
        for (JsonElement element : value.getAsJsonArray()) {
            walk(element, path + "/*", row, fields);
        }
    }

    private static List<JsonElement> values(JsonObject row, String path) {
        if (row.has(path)) return List.of(row.get(path));
        if (path == null || path.isEmpty() || path.equals("/")) return List.of(row);
        List<JsonElement> current = List.of(row);
        for (String raw : path.substring(path.startsWith("/") ? 1 : 0).split("/", -1)) {
            String part = unescape(raw);
            List<JsonElement> next = new ArrayList<>();
            for (JsonElement value : current) {
                if (part.equals("*") && value.isJsonArray()) {
                    value.getAsJsonArray().forEach(next::add);
                } else if (value.isJsonObject() && value.getAsJsonObject().has(part)) {
                    next.add(value.getAsJsonObject().get(part));
                }
            }
            current = List.copyOf(next);
        }
        return current;
    }

    private static JsonElement direct(JsonObject row, String path) {
        List<JsonElement> found = values(row, path);
        return found.size() == 1 ? found.getFirst() : null;
    }

    private static void replace(JsonObject row, String path, JsonElement replacement) {
        String[] parts = path.substring(path.startsWith("/") ? 1 : 0).split("/", -1);
        JsonObject cursor = row;
        for (int index = 0; index < parts.length - 1; index++) {
            String part = unescape(parts[index]);
            JsonElement child = cursor.get(part);
            if (child == null || !child.isJsonObject()) {
                throw new IllegalArgumentException("EXPAND path is not an object path: " + path);
            }
            cursor = child.getAsJsonObject();
        }
        cursor.add(unescape(parts[parts.length - 1]), replacement);
    }

    private static List<JsonElement> primitives(JsonElement value) {
        List<JsonElement> result = new ArrayList<>();
        collectPrimitives(value, result);
        return List.copyOf(result);
    }

    private static void collectPrimitives(JsonElement value, List<JsonElement> result) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonPrimitive()) {
            result.add(value);
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(element -> collectPrimitives(element, result));
        } else {
            value.getAsJsonObject().entrySet().forEach(entry -> {
                result.add(new JsonPrimitive(entry.getKey()));
                collectPrimitives(entry.getValue(), result);
            });
        }
    }

    private static JsonElement single(JsonObject row, String field) {
        List<JsonElement> found = values(row, field);
        return found.isEmpty() ? JsonNull.INSTANCE : found.getFirst();
    }

    private static void requireSingleScalar(JsonObject row, String field, String operation) {
        List<JsonElement> found = values(row, field);
        if (found.size() > 1 || found.stream().anyMatch(value -> !value.isJsonPrimitive())) {
            throw new IllegalArgumentException(
                    operation + " requires one scalar per row; EXPAND the parent array first: " + field);
        }
    }

    private static int compareValues(JsonElement left, JsonElement right) {
        if (!present(left)) return present(right) ? 1 : 0;
        if (!present(right)) return -1;
        try {
            return numeric(left).compareTo(numeric(right));
        } catch (IllegalArgumentException ignored) {
            return text(left).compareTo(text(right));
        }
    }

    private static BigDecimal numeric(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("numeric operation requires a scalar number");
        }
        return numeric(value.getAsString());
    }

    private static BigDecimal numeric(String value) {
        try {
            return new BigDecimal(value.strip());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("numeric operation requires numeric values");
        }
    }

    private static boolean present(JsonElement value) {
        return value != null && !value.isJsonNull()
                && (!value.isJsonPrimitive() || !value.getAsString().isBlank());
    }

    private static String text(JsonElement value) {
        return value == null || value.isJsonNull() ? "" : canonical(
                value.isJsonPrimitive() ? value.getAsString() : value.toString());
    }

    private static String stringAt(JsonObject row, String path) {
        JsonElement value = single(row, path);
        return value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static Map<String, JsonElement> toMap(JsonObject object) {
        Map<String, JsonElement> result = new TreeMap<>();
        object.entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().deepCopy()));
        return result;
    }

    private static Map<String, JsonElement> copyRow(Map<String, JsonElement> row) {
        Map<String, JsonElement> result = new TreeMap<>();
        row.forEach((key, value) -> result.put(key, value.deepCopy()));
        return Map.copyOf(result);
    }

    private static List<String> requiredFields(List<String> fields, int index) {
        if (fields == null || fields.isEmpty()
                || fields.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("pipeline stage " + index + " requires fields");
        }
        return List.copyOf(fields);
    }

    private static int requiredCount(Integer count, int index) {
        if (count == null || count < 1) {
            throw new IllegalArgumentException("pipeline stage " + index + " requires a positive count");
        }
        return count;
    }

    private static <T> T required(T value, int index, String field) {
        if (value == null || value instanceof String text && text.isBlank()) {
            throw new IllegalArgumentException("pipeline stage " + index + " requires " + field);
        }
        return value;
    }

    private static String canonical(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).strip();
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static String unescape(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }

    private static String type(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        if (value.isJsonArray()) return "array";
        if (value.isJsonObject()) return "object";
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return "boolean";
        if (primitive.isNumber()) return "number";
        return "string";
    }

    private static List<String> operations(Set<String> types) {
        TreeSet<String> operations = new TreeSet<>();
        operations.add("SELECT");
        if (types.contains("array")) operations.add("EXPAND");
        if (types.stream().anyMatch(type -> type.equals("string") || type.equals("boolean") || type.equals("number"))) {
            operations.addAll(List.of("FILTER", "GROUP", "SEARCH", "SORT"));
        }
        if (types.contains("number")) operations.add("AGGREGATE");
        return List.copyOf(operations);
    }

    private static final class FieldAccumulator {
        private final Set<Integer> rows = new TreeSet<>();
        private final Set<String> types = new TreeSet<>();
        private String example;

        void add(JsonElement value, int row) {
            rows.add(row);
            types.add(type(value));
            if (example == null && value != null && !value.isJsonNull()) example = value.toString();
        }

        Field field(String path, int totalRows) {
            return new Field(
                    path,
                    List.copyOf(types),
                    rows.size(),
                    totalRows,
                    example == null ? "null" : example,
                    operations(types));
        }
    }
}
