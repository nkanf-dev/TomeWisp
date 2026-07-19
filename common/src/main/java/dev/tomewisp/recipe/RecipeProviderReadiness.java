package dev.tomewisp.recipe;

import java.util.Objects;

/** Development-probe view of whether required recipe viewers can be sampled safely. */
public record RecipeProviderReadiness(State state, String code, String message) {
    public RecipeProviderReadiness {
        Objects.requireNonNull(state, "state");
        code = require(code, "code");
        message = require(message, "message");
    }

    public static RecipeProviderReadiness ready() {
        return new RecipeProviderReadiness(State.READY, "ready", "Recipe providers are ready");
    }

    public static RecipeProviderReadiness waiting(String code, String message) {
        return new RecipeProviderReadiness(State.WAITING, code, message);
    }

    public static RecipeProviderReadiness failed(String code, String message) {
        return new RecipeProviderReadiness(State.FAILED, code, message);
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum State {
        READY,
        WAITING,
        FAILED
    }
}
