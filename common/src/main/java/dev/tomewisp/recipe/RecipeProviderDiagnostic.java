package dev.tomewisp.recipe;

import dev.tomewisp.context.RecipeReference;
import java.util.Objects;

public record RecipeProviderDiagnostic(String sourceId, String code, String message) {
    public RecipeProviderDiagnostic {
        sourceId = RecipeReference.requireSourceId(sourceId);
        code = require(code, "code");
        message = require(message, "message");
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
