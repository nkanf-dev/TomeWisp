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
import java.util.Set;

public final class ToolSchemaGenerator {
    public JsonObject generate(Class<?> inputType) {
        return schema(inputType, new HashSet<>());
    }

    private JsonObject schema(Type type, Set<Type> visiting) {
        if (type instanceof Class<?> raw) {
            return classSchema(raw, visiting);
        }
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            if (raw == Optional.class) {
                return schema(arguments[0], visiting);
            }
            if (Collection.class.isAssignableFrom(raw)) {
                JsonObject result = typed("array");
                result.add("items", schema(arguments[0], visiting));
                return result;
            }
            if (Map.class.isAssignableFrom(raw) && arguments[0] == String.class) {
                JsonObject result = typed("object");
                result.add("additionalProperties", schema(arguments[1], visiting));
                return result;
            }
        }
        if (type instanceof GenericArrayType array) {
            JsonObject result = typed("array");
            result.add("items", schema(array.getGenericComponentType(), visiting));
            return result;
        }
        throw new IllegalArgumentException("Unsupported tool schema type: " + type.getTypeName());
    }

    private JsonObject classSchema(Class<?> type, Set<Type> visiting) {
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
            result.add("items", schema(type.getComponentType(), visiting));
            return result;
        }
        if (type.isRecord()) {
            if (!visiting.add(type)) {
                throw new IllegalArgumentException("Recursive tool record is unsupported: " + type.getName());
            }
            JsonObject result = typed("object");
            JsonObject properties = new JsonObject();
            JsonArray required = new JsonArray();
            for (RecordComponent component : type.getRecordComponents()) {
                properties.add(component.getName(), schema(component.getGenericType(), visiting));
                if (component.getAnnotation(ToolOptional.class) == null
                        && !(component.getGenericType() instanceof ParameterizedType parameterized
                                && parameterized.getRawType() == Optional.class)) {
                    required.add(component.getName());
                }
            }
            result.add("properties", properties);
            result.add("required", required);
            result.addProperty("additionalProperties", false);
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
