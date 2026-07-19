package dev.openallay.capability;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Objects;

/** Canonical credential-free encoder for local capability policy. */
public final class CapabilityPolicyWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String encode(CapabilityPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", policy.schemaVersion());
        root.add("disabledTools", array(policy.disabledTools()));
        root.add("disabledSkills", array(policy.disabledSkills()));
        return GSON.toJson(root) + System.lineSeparator();
    }

    private static JsonArray array(Iterable<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
