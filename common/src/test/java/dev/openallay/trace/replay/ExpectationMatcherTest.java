package dev.openallay.trace.replay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.trace.model.ExpectationMatch;
import dev.openallay.trace.model.TraceExpectation;
import org.junit.jupiter.api.Test;

final class ExpectationMatcherTest {
    private final ExpectationMatcher matcher = new ExpectationMatcher();

    @Test
    void exactIsKeyOrderIndependentAndNumbersCompareByValue() {
        JsonObject actual = object(
                "{\"status\":\"success\",\"outputType\":\"example.Output\",\"value\":{\"b\":[1.0,2],\"a\":true}}");
        TraceExpectation expectation = new TraceExpectation(
                "success",
                ExpectationMatch.EXACT,
                JsonParser.parseString("{\"a\":true,\"b\":[1,2.00]}"),
                null);

        assertTrue(matcher.match(expectation, actual).matches());
    }

    @Test
    void containsUsesRecursiveObjectSubsetAndOrderedArrayPrefix() {
        JsonObject actual = object(
                "{\"status\":\"success\",\"outputType\":\"example.Output\",\"value\":{\"items\":[{\"id\":\"a\",\"count\":1},{\"id\":\"b\"}]}}");
        TraceExpectation passing = new TraceExpectation(
                "success",
                ExpectationMatch.CONTAINS,
                JsonParser.parseString("{\"items\":[{\"id\":\"a\"}]}"),
                null);
        TraceExpectation reordered = new TraceExpectation(
                "success",
                ExpectationMatch.CONTAINS,
                JsonParser.parseString("{\"items\":[{\"id\":\"b\"}]}"),
                null);

        assertTrue(matcher.match(passing, actual).matches());
        assertFalse(matcher.match(reordered, actual).matches());
    }

    @Test
    void schemaChecksStatusAndDeclaredOutputType() {
        JsonObject actual = object(
                "{\"status\":\"success\",\"outputType\":\"example.Output\",\"value\":{}}");
        assertTrue(matcher
                .match(
                        new TraceExpectation(
                                "success", ExpectationMatch.SCHEMA, null, "example.Output"),
                        actual)
                .matches());
        assertFalse(matcher
                .match(
                        new TraceExpectation(
                                "success", ExpectationMatch.SCHEMA, null, "other.Output"),
                        actual)
                .matches());
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
