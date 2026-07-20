package dev.openallay.resource.vfs;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface ResourceValue permits ResourceValue.Scalar, ResourceValue.RecordValue,
        ResourceValue.ListValue, ResourceValue.TableValue, ResourceValue.DocumentValue,
        ResourceValue.DirectoryValue, ResourceValue.BinaryMetadataValue,
        ResourceValue.FailureValue, ResourceValue.ReferenceValue {

    record Scalar(Object value) implements ResourceValue {
        public Scalar {
            if (!(value == null || value instanceof String || value instanceof Boolean || value instanceof BigDecimal)) {
                throw new IllegalArgumentException("Resource scalar must be null, string, boolean, or BigDecimal");
            }
        }

        public static Scalar number(Number value) {
            return new Scalar(new BigDecimal(Objects.requireNonNull(value, "value").toString()));
        }
    }

    record RecordValue(Map<String, ResourceValue> fields) implements ResourceValue {
        public RecordValue {
            LinkedHashMap<String, ResourceValue> copy = new LinkedHashMap<>();
            Objects.requireNonNull(fields, "fields").forEach((key, value) -> {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("Resource field name is required");
                }
                copy.put(key, Objects.requireNonNull(value, "field value"));
            });
            fields = Collections.unmodifiableMap(copy);
        }
    }

    record ListValue(List<ResourceValue> values) implements ResourceValue {
        public ListValue {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    record TableValue(List<String> columns, List<List<ResourceValue>> rows) implements ResourceValue {
        public TableValue {
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
            if (columns.isEmpty() || columns.stream().anyMatch(column -> column == null || column.isBlank())) {
                throw new IllegalArgumentException("Table columns are required");
            }
            ArrayList<List<ResourceValue>> copy = new ArrayList<>();
            for (List<ResourceValue> row : Objects.requireNonNull(rows, "rows")) {
                List<ResourceValue> rowCopy = List.copyOf(row);
                if (rowCopy.size() != columns.size()) {
                    throw new IllegalArgumentException("Table row width differs from columns");
                }
                copy.add(rowCopy);
            }
            rows = List.copyOf(copy);
        }
    }

    record DocumentValue(String title, List<DocumentSection> sections) implements ResourceValue {
        public DocumentValue {
            title = requireText(title, "title");
            sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
        }
    }

    record DocumentSection(String id, String heading, String text) {
        public DocumentSection {
            id = requireText(id, "id");
            heading = requireText(heading, "heading");
            text = requireText(text, "text");
        }
    }

    record DirectoryValue(int childCount) implements ResourceValue {
        public DirectoryValue {
            if (childCount < 0) {
                throw new IllegalArgumentException("childCount must be non-negative");
            }
        }
    }

    record BinaryMetadataValue(long size, String sha256, String mediaType) implements ResourceValue {
        public BinaryMetadataValue {
            if (size < 0) {
                throw new IllegalArgumentException("size must be non-negative");
            }
            sha256 = requireText(sha256, "sha256");
            mediaType = requireText(mediaType, "mediaType");
        }
    }

    record FailureValue(String code, String message) implements ResourceValue {
        public FailureValue {
            code = requireText(code, "code");
            message = requireText(message, "message");
        }
    }

    record ReferenceValue(ResourcePath target, String relation) implements ResourceValue {
        public ReferenceValue {
            Objects.requireNonNull(target, "target");
            relation = requireText(relation, "relation");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
