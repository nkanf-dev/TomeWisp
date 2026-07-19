package dev.openallay.guide.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.guide.GuideFailure;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public final class GuideDisplayConfigLoader {
    private static final Set<String> FIELDS = Set.of(
            "schemaVersion", "debugMode", "animationsEnabled", "assistantName");

    public record Load(GuideDisplayConfig config, GuideFailure failure) {
        public Load {
            Objects.requireNonNull(config, "config");
        }
    }

    public Load load(Path path) {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            return new Load(GuideDisplayConfig.defaults(), null);
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            return load(reader);
        } catch (IOException failure) {
            return invalid(failure);
        }
    }

    public Load load(Reader reader) {
        Objects.requireNonNull(reader, "reader");
        try {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Display configuration must be an object");
            }
            JsonObject object = parsed.getAsJsonObject();
            if (!object.keySet().equals(FIELDS)) {
                Set<String> missing = new java.util.TreeSet<>(FIELDS);
                missing.removeAll(object.keySet());
                Set<String> extra = new java.util.TreeSet<>(object.keySet());
                extra.removeAll(FIELDS);
                throw new IllegalArgumentException(
                        "Display configuration schema mismatch; missing=" + missing
                                + ", extra=" + extra);
            }
            int version = integer(object, "schemaVersion");
            boolean debugMode = bool(object, "debugMode");
            boolean animationsEnabled = bool(object, "animationsEnabled");
            String assistantName = string(object, "assistantName");
            return new Load(
                    new GuideDisplayConfig(
                            version, debugMode, animationsEnabled, assistantName), null);
        } catch (RuntimeException failure) {
            return invalid(failure);
        }
    }

    private static Load invalid(Exception failure) {
        String message = failure.getMessage();
        return new Load(
                GuideDisplayConfig.defaults(),
                new GuideFailure(
                        "invalid_display_config",
                        message == null || message.isBlank()
                                ? "Invalid display configuration"
                                : message));
    }

    private static int integer(JsonObject object, String field) {
        JsonElement value = object.get(field);
        try {
            if (value == null || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + " must be an integer", failure);
        }
    }

    private static boolean bool(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.getAsString();
    }
}
