package dev.tomewisp.guide.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.Objects;

/** Canonical credential-free encoder for local Guide presentation settings. */
public final class GuideDisplayConfigWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String encode(GuideDisplayConfig config) {
        Objects.requireNonNull(config, "config");
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", config.schemaVersion());
        root.addProperty("debugMode", config.debugMode());
        root.addProperty("animationsEnabled", config.animationsEnabled());
        return GSON.toJson(root) + System.lineSeparator();
    }
}
