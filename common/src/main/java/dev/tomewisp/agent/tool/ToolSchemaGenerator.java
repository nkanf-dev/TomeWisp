package dev.tomewisp.agent.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;
import java.util.Set;

public final class ToolSchemaGenerator {
    public JsonObject generate(Class<?> inputType) {
        return schema(inputType, new HashSet<>(), true);
    }

    /** Generates the serialized result shape without claiming every nullable result field is present. */
    public JsonObject generateOutput(Class<?> outputType) {
        return schema(outputType, new HashSet<>(), false);
    }

    private JsonObject schema(Type type, Set<Type> visiting, boolean inputContract) {
        if (type instanceof Class<?> raw) {
            return classSchema(raw, visiting, inputContract);
        }
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            if (raw == Optional.class) {
                return schema(arguments[0], visiting, inputContract);
            }
            if (Collection.class.isAssignableFrom(raw)) {
                JsonObject result = typed("array");
                result.add("items", schema(arguments[0], visiting, inputContract));
                return result;
            }
            if (Map.class.isAssignableFrom(raw) && arguments[0] == String.class) {
                JsonObject result = typed("object");
                result.add("additionalProperties", schema(arguments[1], visiting, inputContract));
                return result;
            }
        }
        if (type instanceof GenericArrayType array) {
            JsonObject result = typed("array");
            result.add("items", schema(array.getGenericComponentType(), visiting, inputContract));
            return result;
        }
        throw new IllegalArgumentException("Unsupported tool schema type: " + type.getTypeName());
    }

    private JsonObject classSchema(Class<?> type, Set<Type> visiting, boolean inputContract) {
        if (type == String.class || type == Character.class || type == char.class) {
            return typed("string");
        }
        if (type == boolean.class || type == Boolean.class) {
            return typed("boolean");
        }
        if (type == byte.class
                || type == short.class
                || type == int.class
                || type == long.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == BigInteger.class) {
            return typed("integer");
        }
        if (type == float.class
                || type == double.class
                || type == Float.class
                || type == Double.class
                || type == BigDecimal.class) {
            return typed("number");
        }
        if (type.isEnum()) {
            JsonObject result = typed("string");
            JsonArray values = new JsonArray();
            for (Object constant : type.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            result.add("enum", values);
            return result;
        }
        if (type.isArray()) {
            JsonObject result = typed("array");
            result.add("items", schema(type.getComponentType(), visiting, inputContract));
            return result;
        }
        if (type == java.time.Instant.class
                || type == java.net.URI.class
                || type == java.util.UUID.class) {
            JsonObject result = typed("string");
            result.addProperty("format", type == java.time.Instant.class
                    ? "date-time"
                    : type == java.util.UUID.class ? "uuid" : "uri");
            return result;
        }
        if (com.google.gson.JsonObject.class.isAssignableFrom(type)) {
            return typed("object");
        }
        if (com.google.gson.JsonArray.class.isAssignableFrom(type)) {
            return typed("array");
        }
        if (com.google.gson.JsonElement.class.isAssignableFrom(type) || type == Object.class) {
            return new JsonObject();
        }
        if (type.isRecord()) {
            if (!visiting.add(type)) {
                throw new IllegalArgumentException("Recursive tool record is unsupported: " + type.getName());
            }
            JsonObject result = typed("object");
            ToolDescription recordDescription = type.getAnnotation(ToolDescription.class);
            if (recordDescription != null) {
                result.addProperty("description", recordDescription.value());
            }
            JsonObject properties = new JsonObject();
            JsonArray required = new JsonArray();
            for (RecordComponent component : type.getRecordComponents()) {
                JsonObject componentSchema = schema(
                        component.getGenericType(), visiting, inputContract);
                ToolDescription description = component.getAnnotation(ToolDescription.class);
                if (description != null) {
                    componentSchema.addProperty("description", description.value());
                }
                ToolPattern pattern = component.getAnnotation(ToolPattern.class);
                if (pattern != null) {
                    componentSchema.addProperty("pattern", pattern.value());
                }
                properties.add(component.getName(), componentSchema);
                if (inputContract
                        && component.getAnnotation(ToolOptional.class) == null
                        && !(component.getGenericType() instanceof ParameterizedType parameterized
                                && parameterized.getRawType() == Optional.class)) {
                    required.add(component.getName());
                }
            }
            result.add("properties", properties);
            result.add("required", required);
            result.addProperty("additionalProperties", false);
            ToolAtLeastOne atLeastOne = inputContract
                    ? type.getAnnotation(ToolAtLeastOne.class) : null;
            if (atLeastOne != null) {
                Set<String> componentNames = Arrays.stream(type.getRecordComponents())
                        .map(RecordComponent::getName)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                JsonArray anyOf = new JsonArray();
                for (String name : atLeastOne.value()) {
                    if (!componentNames.contains(name)) {
                        throw new IllegalArgumentException(
                                "Unknown at-least-one component " + name + " on " + type.getName());
                    }
                    JsonObject alternative = new JsonObject();
                    JsonArray alternativeRequired = new JsonArray();
                    alternativeRequired.add(name);
                    alternative.add("required", alternativeRequired);
                    anyOf.add(alternative);
                }
                if (anyOf.isEmpty()) {
                    throw new IllegalArgumentException(
                            "At-least-one Tool contract must name a component: " + type.getName());
                }
                result.add("anyOf", anyOf);
            }
            visiting.remove(type);
            return result;
        }
        throw new IllegalArgumentException("Unsupported tool schema class: " + type.getName());
    }

    private static JsonObject typed(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        return object;
    }
}
