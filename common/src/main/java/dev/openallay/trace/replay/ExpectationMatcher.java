package dev.openallay.trace.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.openallay.trace.model.ExpectationMatch;
import dev.openallay.trace.model.TraceExpectation;
import java.math.BigDecimal;

public final class ExpectationMatcher {
    public record MatchResult(boolean matches, String error) {
        public static MatchResult passed() {
            return new MatchResult(true, null);
        }

        public static MatchResult failed(String error) {
            return new MatchResult(false, error);
        }
    }

    public MatchResult match(TraceExpectation expectation, JsonObject actual) {
        String actualStatus = stringField(actual, "status");
        if (!expectation.status().equals(actualStatus)) {
            return MatchResult.failed(
                    "expected status " + expectation.status() + " but was " + actualStatus);
        }
        if (expectation.match() == ExpectationMatch.SCHEMA) {
            String actualType = stringField(actual, "outputType");
            if (!expectation.outputType().equals(actualType)) {
                return MatchResult.failed(
                        "expected outputType " + expectation.outputType() + " but was " + actualType);
            }
            return MatchResult.passed();
        }

        JsonElement actualValue = payload(actual, actualStatus);
        boolean matches = expectation.match() == ExpectationMatch.EXACT
                ? equivalent(expectation.value(), actualValue)
                : contains(actualValue, expectation.value());
        return matches
                ? MatchResult.passed()
                : MatchResult.failed(expectation.match().name().toLowerCase()
                        + " expectation did not match actual value");
    }

    private static JsonElement payload(JsonObject actual, String status) {
        if ("success".equals(status)) {
            return actual.get("value");
        }
        JsonObject failure = actual.deepCopy();
        failure.remove("status");
        return failure;
    }

    private static boolean contains(JsonElement actual, JsonElement expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        if (expected.isJsonObject()) {
            if (!actual.isJsonObject()) {
                return false;
            }
            JsonObject actualObject = actual.getAsJsonObject();
            for (var entry : expected.getAsJsonObject().entrySet()) {
                if (!actualObject.has(entry.getKey())
                        || !contains(actualObject.get(entry.getKey()), entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isJsonArray()) {
            if (!actual.isJsonArray()) {
                return false;
            }
            JsonArray expectedArray = expected.getAsJsonArray();
            JsonArray actualArray = actual.getAsJsonArray();
            if (expectedArray.size() > actualArray.size()) {
                return false;
            }
            for (int index = 0; index < expectedArray.size(); index++) {
                if (!contains(actualArray.get(index), expectedArray.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return equivalent(expected, actual);
    }

    private static boolean equivalent(JsonElement left, JsonElement right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.isJsonPrimitive() && right.isJsonPrimitive()) {
            JsonPrimitive leftPrimitive = left.getAsJsonPrimitive();
            JsonPrimitive rightPrimitive = right.getAsJsonPrimitive();
            if (leftPrimitive.isNumber() && rightPrimitive.isNumber()) {
                try {
                    return new BigDecimal(leftPrimitive.getAsString())
                                    .compareTo(new BigDecimal(rightPrimitive.getAsString()))
                            == 0;
                } catch (NumberFormatException ignored) {
                    return left.equals(right);
                }
            }
        }
        if (left.isJsonArray() && right.isJsonArray()) {
            JsonArray leftArray = left.getAsJsonArray();
            JsonArray rightArray = right.getAsJsonArray();
            if (leftArray.size() != rightArray.size()) {
                return false;
            }
            for (int index = 0; index < leftArray.size(); index++) {
                if (!equivalent(leftArray.get(index), rightArray.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isJsonObject() && right.isJsonObject()) {
            JsonObject leftObject = left.getAsJsonObject();
            JsonObject rightObject = right.getAsJsonObject();
            if (!leftObject.keySet().equals(rightObject.keySet())) {
                return false;
            }
            for (String key : leftObject.keySet()) {
                if (!equivalent(leftObject.get(key), rightObject.get(key))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    private static String stringField(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
