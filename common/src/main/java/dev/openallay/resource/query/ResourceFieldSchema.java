package dev.openallay.resource.query;

import dev.openallay.resource.vfs.ResourceValue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Runtime-discovered VFS field vocabulary; no Minecraft domain field is built in. */
public record ResourceFieldSchema(List<Field> fields, int totalRows) {
    public enum Type { NULL, STRING, BOOLEAN, NUMBER, RECORD, LIST, TABLE, DOCUMENT, REFERENCE, BINARY, FAILURE }

    public enum Operation { SEARCH, FILTER, SELECT, SORT, GROUP, AGGREGATE, EXPAND }

    public record Field(
            String path,
            List<Type> types,
            int presentRows,
            int totalRows,
            List<Operation> operations) {
        public Field {
            if (path == null || path.isBlank() || !path.startsWith("/")) {
                throw new IllegalArgumentException("Schema field path must be an RFC 6901-style pointer");
            }
            types = List.copyOf(Objects.requireNonNull(types, "types"));
            operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
            if (presentRows < 0 || totalRows < presentRows) {
                throw new IllegalArgumentException("Invalid schema field cardinality");
            }
        }
    }

    public ResourceFieldSchema {
        fields = fields.stream().sorted(java.util.Comparator.comparing(Field::path)).toList();
        if (totalRows < 0) throw new IllegalArgumentException("totalRows must be non-negative");
    }

    public Field require(String path, Operation operation) {
        Field field = fields.stream().filter(candidate -> candidate.path().equals(path)).findFirst()
                .orElseThrow(() -> new ResourceQueryException("field_unavailable", path,
                        "Unknown field '" + path + "'; available fields: " + availablePaths()));
        if (!field.operations().contains(operation)) {
            throw new ResourceQueryException("operation_unavailable", path,
                    operation + " is unavailable for field '" + path + "' with types " + field.types());
        }
        return field;
    }

    public List<String> availablePaths() {
        return fields.stream().map(Field::path).toList();
    }

    /** Discover an explicit schema from the exact rows selected by a captured view. */
    public static ResourceFieldSchema discover(Collection<? extends Map<String, ResourceValue>> rows) {
        Objects.requireNonNull(rows, "rows");
        TreeMap<String, Accumulator> fields = new TreeMap<>();
        int rowIndex = 0;
        for (Map<String, ResourceValue> row : rows) {
            for (Map.Entry<String, ResourceValue> field : row.entrySet()) {
                String path = field.getKey().startsWith("/") ? field.getKey() : "/" + escape(field.getKey());
                walk(field.getValue(), path, rowIndex, fields);
            }
            rowIndex++;
        }
        int totalRows = rows.size();
        return new ResourceFieldSchema(fields.entrySet().stream()
                .map(entry -> entry.getValue().field(entry.getKey(), totalRows))
                .toList(), totalRows);
    }

    private static void walk(
            ResourceValue value, String path, int rowIndex, Map<String, Accumulator> fields) {
        fields.computeIfAbsent(path, ignored -> new Accumulator()).add(type(value), rowIndex);
        switch (value) {
            case ResourceValue.RecordValue record -> record.fields().forEach((key, child) ->
                    walk(child, path + "/" + escape(key), rowIndex, fields));
            case ResourceValue.ListValue list -> list.values().forEach(child ->
                    walk(child, path + "/*", rowIndex, fields));
            default -> { }
        }
    }

    private static Type type(ResourceValue value) {
        return switch (value) {
            case ResourceValue.Scalar scalar -> scalar.value() == null ? Type.NULL
                    : scalar.value() instanceof String ? Type.STRING
                    : scalar.value() instanceof Boolean ? Type.BOOLEAN : Type.NUMBER;
            case ResourceValue.RecordValue ignored -> Type.RECORD;
            case ResourceValue.ListValue ignored -> Type.LIST;
            case ResourceValue.TableValue ignored -> Type.TABLE;
            case ResourceValue.DocumentValue ignored -> Type.DOCUMENT;
            case ResourceValue.ReferenceValue ignored -> Type.REFERENCE;
            case ResourceValue.BinaryMetadataValue ignored -> Type.BINARY;
            case ResourceValue.FailureValue ignored -> Type.FAILURE;
            case ResourceValue.DirectoryValue ignored -> Type.RECORD;
        };
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static final class Accumulator {
        private final TreeSet<Type> types = new TreeSet<>();
        private final TreeSet<Integer> rows = new TreeSet<>();

        private void add(Type type, int row) {
            types.add(type);
            rows.add(row);
        }

        private Field field(String path, int totalRows) {
            EnumSet<Operation> operations = EnumSet.of(Operation.SEARCH, Operation.SELECT);
            if (types.stream().allMatch(type -> type == Type.NULL || type == Type.STRING
                    || type == Type.BOOLEAN || type == Type.NUMBER)) {
                operations.add(Operation.FILTER);
                operations.add(Operation.SORT);
                operations.add(Operation.GROUP);
            }
            if (types.stream().allMatch(type -> type == Type.NULL || type == Type.NUMBER)) {
                operations.add(Operation.AGGREGATE);
            }
            if (types.stream().allMatch(type -> type == Type.NULL || type == Type.LIST)) {
                operations.add(Operation.EXPAND);
            }
            return new Field(path, List.copyOf(types), rows.size(), totalRows, List.copyOf(operations));
        }
    }
}
