package dev.tomewisp.model.config;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Canonical credential-free encoder paired with {@link ModelProfilesConfigLoader}. */
public final class ModelProfilesConfigWriter {
    private final Gson gson = new Gson();

    public String encode(ModelProfilesConfig config) {
        Objects.requireNonNull(config, "config");
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", config.schemaVersion());
        root.addProperty("defaultProfileId", config.defaultProfileId());
        root.add("profiles", encodeProfiles(config.profiles()));
        return gson.toJson(root) + System.lineSeparator();
    }

    private static JsonArray encodeProfiles(List<ModelProfileDefinition> profiles) {
        JsonArray encoded = new JsonArray();
        profiles.stream().map(ModelProfilesConfigWriter::encodeProfile).forEach(encoded::add);
        return encoded;
    }

    private static JsonObject encodeProfile(ModelProfileDefinition profile) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("id", profile.id());
        encoded.addProperty("displayName", profile.displayName());
        encoded.addProperty("enabled", profile.enabled());
        encoded.addProperty("protocol", profile.protocol().name().toLowerCase(Locale.ROOT));
        encoded.addProperty("baseUrl", profile.baseUri().toString());
        encoded.addProperty("model", profile.model());
        encoded.addProperty("credentialRef", profile.credentialRef());
        if (profile.contextWindowTokens() != null) {
            encoded.addProperty("contextWindowTokens", profile.contextWindowTokens());
        }
        encoded.addProperty("maxOutputTokens", profile.maxOutputTokens());
        encoded.addProperty("connectTimeoutSeconds", exactSeconds(profile.connectTimeout()));
        encoded.addProperty("requestTimeoutSeconds", exactSeconds(profile.requestTimeout()));
        if (profile.metadata() != null) {
            JsonObject metadata = new JsonObject();
            metadata.addProperty("source", profile.metadata().source());
            metadata.addProperty("upstreamModelId", profile.metadata().upstreamModelId());
            metadata.addProperty("capturedAt", profile.metadata().capturedAt().toString());
            encoded.add("metadata", metadata);
        }
        return encoded;
    }

    private static int exactSeconds(Duration duration) {
        long seconds = duration.toSeconds();
        if (!duration.equals(Duration.ofSeconds(seconds))
                || seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Model timeout must be representable as whole integer seconds");
        }
        return Math.toIntExact(seconds);
    }
}
