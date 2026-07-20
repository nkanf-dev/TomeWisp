package dev.openallay.resource.vfs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ResourceValues {
    private ResourceValues() {}

    public static ResourceValue fromJson(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return new ResourceValue.Scalar(null);
        }
        if (value instanceof JsonPrimitive primitive) {
            if (primitive.isBoolean()) {
                return new ResourceValue.Scalar(primitive.getAsBoolean());
            }
            if (primitive.isNumber()) {
                return new ResourceValue.Scalar(new BigDecimal(primitive.getAsString()));
            }
            return new ResourceValue.Scalar(primitive.getAsString());
        }
        if (value instanceof JsonArray array) {
            ArrayList<ResourceValue> values = new ArrayList<>(array.size());
            array.forEach(element -> values.add(fromJson(element)));
            return new ResourceValue.ListValue(values);
        }
        JsonObject object = value.getAsJsonObject();
        LinkedHashMap<String, ResourceValue> fields = new LinkedHashMap<>();
        object.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> fields.put(entry.getKey(), fromJson(entry.getValue())));
        return new ResourceValue.RecordValue(fields);
    }

    public static ResourceValue strings(Iterable<String> values) {
        ArrayList<ResourceValue> result = new ArrayList<>();
        values.forEach(value -> result.add(new ResourceValue.Scalar(value)));
        return new ResourceValue.ListValue(result);
    }

    public static ResourceValue record(Map<String, ?> values) {
        LinkedHashMap<String, ResourceValue> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, scalarOrValue(value)));
        return new ResourceValue.RecordValue(result);
    }

    public static ResourceValue scalarOrValue(Object value) {
        if (value instanceof ResourceValue resourceValue) {
            return resourceValue;
        }
        if (value instanceof Number number) {
            return ResourceValue.Scalar.number(number);
        }
        if (value instanceof String || value instanceof Boolean || value == null) {
            return new ResourceValue.Scalar(value);
        }
        throw new IllegalArgumentException("Unsupported resource value: " + value.getClass().getName());
    }

    public static String typeName(ResourceValue value) {
        return switch (value) {
            case ResourceValue.Scalar scalar -> scalar.value() == null ? "null" : scalar.value().getClass().getSimpleName().toLowerCase();
            case ResourceValue.RecordValue ignored -> "record";
            case ResourceValue.ListValue ignored -> "list";
            case ResourceValue.TableValue ignored -> "table";
            case ResourceValue.DocumentValue ignored -> "document";
            case ResourceValue.DirectoryValue ignored -> "directory";
            case ResourceValue.BinaryMetadataValue ignored -> "binary_metadata";
            case ResourceValue.FailureValue ignored -> "failure";
            case ResourceValue.ReferenceValue ignored -> "reference";
        };
    }
}
