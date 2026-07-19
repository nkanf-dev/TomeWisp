package dev.tomewisp.model.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict ordered profile document stored without credential values. */
public record ModelProfilesConfig(
        int schemaVersion,
        String defaultProfileId,
        List<ModelProfileDefinition> profiles) {
    public static final int SCHEMA_VERSION = 2;

    public ModelProfilesConfig {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported model profiles schema version " + schemaVersion);
        }
        if (defaultProfileId == null || !defaultProfileId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid defaultProfileId");
        }
        profiles = List.copyOf(profiles);
        Set<String> ids = new HashSet<>();
        for (ModelProfileDefinition profile : profiles) {
            if (!ids.add(profile.id())) {
                throw new IllegalArgumentException("duplicate model profile id " + profile.id());
            }
        }
        if (!ids.contains(defaultProfileId)) {
            throw new IllegalArgumentException("defaultProfileId does not name a profile");
        }
    }
}
