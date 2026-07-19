package dev.openallay.trace.model;

import com.google.gson.JsonElement;
import java.util.Objects;

public record TraceExpectation(
        String status, ExpectationMatch match, JsonElement value, String outputType) {
    public TraceExpectation {
        if (!"success".equals(status) && !"failure".equals(status)) {
            throw new IllegalArgumentException("Expectation status must be success or failure");
        }
        Objects.requireNonNull(match, "match");
        value = value == null ? null : value.deepCopy();
        if ((match == ExpectationMatch.EXACT || match == ExpectationMatch.CONTAINS)
                && value == null) {
            throw new IllegalArgumentException("Exact and contains expectations require value");
        }
        if (match == ExpectationMatch.SCHEMA
                && (outputType == null || outputType.isBlank())) {
            throw new IllegalArgumentException("Schema expectations require outputType");
        }
    }

    @Override
    public JsonElement value() {
        return value == null ? null : value.deepCopy();
    }
}
