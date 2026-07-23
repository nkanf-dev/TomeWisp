package dev.openallay.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.latvian.mods.rhino.BaseFunction;
import dev.latvian.mods.rhino.NativeArray;
import dev.latvian.mods.rhino.NativeObject;
import dev.latvian.mods.rhino.NativePromise;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.Symbol;
import dev.latvian.mods.rhino.Undefined;
import dev.latvian.mods.rhino.Wrapper;
import java.util.IdentityHashMap;
import java.util.Map;

final class RhinoJsonNormalizer {
    private static final int MAX_DEPTH = 128;

    JsonElement normalize(Object value) {
        return normalize(value, new IdentityHashMap<>(), 0);
    }

    private JsonElement normalize(
            Object value, IdentityHashMap<Object, Boolean> ancestors, int depth) {
        if (depth > MAX_DEPTH) {
            throw invalid("JavaScript result is nested too deeply");
        }
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value == Undefined.INSTANCE || value == Undefined.SCRIPTABLE_INSTANCE) {
            throw invalid("JavaScript result is undefined");
        }
        if (value instanceof Boolean booleanValue) {
            return new JsonPrimitive(booleanValue);
        }
        if (value instanceof CharSequence sequence) {
            return new JsonPrimitive(sequence.toString());
        }
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric)) {
                throw invalid("JavaScript result contains a non-finite number");
            }
            return new JsonPrimitive(number);
        }
        if (value instanceof BaseFunction
                || value instanceof NativePromise
                || value instanceof Symbol
                || value instanceof Wrapper) {
            throw invalid("JavaScript result contains an unsupported host or executable value");
        }
        if (value instanceof NativeArray array) {
            enter(value, ancestors);
            try {
                JsonArray result = new JsonArray();
                for (Object item : array) {
                    result.add(item == Undefined.INSTANCE
                            ? JsonNull.INSTANCE
                            : normalize(item, ancestors, depth + 1));
                }
                return result;
            } finally {
                ancestors.remove(value);
            }
        }
        if (value instanceof NativeObject object) {
            enter(value, ancestors);
            try {
                JsonObject result = new JsonObject();
                for (Object rawEntry : object.entrySet()) {
                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>) rawEntry;
                    Object child = entry.getValue();
                    if (child != Undefined.INSTANCE) {
                        result.add(
                                String.valueOf(entry.getKey()),
                                normalize(child, ancestors, depth + 1));
                    }
                }
                return result;
            } finally {
                ancestors.remove(value);
            }
        }
        if (value instanceof Scriptable) {
            throw invalid("JavaScript result contains an unsupported script object");
        }
        throw invalid("JavaScript result contains an unsupported value");
    }

    private static void enter(Object value, IdentityHashMap<Object, Boolean> ancestors) {
        if (ancestors.put(value, Boolean.TRUE) != null) {
            throw invalid("JavaScript result contains a cycle");
        }
    }

    private static JavascriptExecutionException invalid(String message) {
        return new JavascriptExecutionException("javascript_result_invalid", message);
    }
}

