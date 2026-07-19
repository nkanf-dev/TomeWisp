package dev.tomewisp.recipe.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Objects;

/** Canonical credential-free encoder for recipe Tool settings. */
public final class RecipeClientConfigWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String encode(RecipeClientConfig config) {
        Objects.requireNonNull(config, "config");
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", config.schemaVersion());
        root.addProperty("visibility", config.visibility().name());
        root.addProperty("preferredViewer", config.preferredViewer());
        JsonArray disabled = new JsonArray();
        config.disabledSources().forEach(disabled::add);
        root.add("disabledSources", disabled);
        return GSON.toJson(root) + System.lineSeparator();
    }
}
