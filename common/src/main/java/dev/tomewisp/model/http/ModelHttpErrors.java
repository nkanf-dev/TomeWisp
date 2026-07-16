package dev.tomewisp.model.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.tomewisp.model.ModelClientException;
import dev.tomewisp.model.ModelFailure;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ModelHttpErrors {
    private ModelHttpErrors() {}

    public static void requireSuccess(int status, InputStream body) throws IOException {
        if (status >= 200 && status < 300) {
            return;
        }
        String response = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        String message = "Model endpoint returned HTTP " + status;
        try {
            JsonElement json = JsonParser.parseString(response);
            if (json.isJsonObject() && json.getAsJsonObject().has("error")) {
                JsonElement error = json.getAsJsonObject().get("error");
                if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                    message += ": " + error.getAsJsonObject().get("message").getAsString();
                } else if (error.isJsonPrimitive()) {
                    message += ": " + error.getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // Do not echo arbitrary provider bodies into player-facing diagnostics.
        }
        throw new ModelClientException(new ModelFailure("model_http_error", message, status));
    }
}
