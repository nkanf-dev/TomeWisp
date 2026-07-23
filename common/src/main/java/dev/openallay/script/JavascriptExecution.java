package dev.openallay.script;

import com.google.gson.JsonElement;
import java.time.Duration;
import java.util.Objects;

public record JavascriptExecution(JsonElement value, Duration elapsed) {
    public JavascriptExecution {
        value = Objects.requireNonNull(value, "value").deepCopy();
        Objects.requireNonNull(elapsed, "elapsed");
    }
}

