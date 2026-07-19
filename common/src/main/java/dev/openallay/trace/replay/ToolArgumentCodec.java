package dev.openallay.trace.replay;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.openallay.tool.ToolResult;
import java.util.Objects;

public final class ToolArgumentCodec {
    private final Gson gson;

    public ToolArgumentCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public <I> ToolResult<I> decode(JsonObject arguments, Class<I> inputType) {
        try {
            I input = gson.fromJson(arguments, inputType);
            if (input == null) {
                return new ToolResult.Failure<>(
                        "invalid_arguments", "tool arguments decoded to null");
            }
            return new ToolResult.Success<>(input);
        } catch (JsonParseException | IllegalArgumentException exception) {
            String message = exception.getMessage();
            return new ToolResult.Failure<>(
                    "invalid_arguments",
                    message == null || message.isBlank()
                            ? "tool arguments do not match the declared input type"
                            : message);
        }
    }
}
