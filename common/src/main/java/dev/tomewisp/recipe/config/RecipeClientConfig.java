package dev.tomewisp.recipe.config;

import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.recipe.RecipeVisibilityPolicy;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Generic stable-ID recipe Tool settings for the current pre-release schema. */
public record RecipeClientConfig(
        int schemaVersion,
        RecipeVisibilityPolicy visibility,
        String preferredViewer,
        Set<String> disabledSources) {
    public static final int SCHEMA_VERSION = 2;
    public static final String AUTO = "auto";

    public RecipeClientConfig {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported recipe configuration schema");
        }
        Objects.requireNonNull(visibility, "visibility");
        if (preferredViewer == null || preferredViewer.isBlank()) {
            throw new IllegalArgumentException("preferredViewer must not be blank");
        }
        if (!AUTO.equals(preferredViewer)) {
            preferredViewer = RecipeReference.requireSourceId(preferredViewer);
        }
        Objects.requireNonNull(disabledSources, "disabledSources");
        TreeSet<String> canonical = new TreeSet<>();
        for (String sourceId : disabledSources) {
            String validated = RecipeReference.requireSourceId(sourceId);
            if (!canonical.add(validated)) {
                throw new IllegalArgumentException("disabledSources must not contain duplicates");
            }
        }
        disabledSources = Collections.unmodifiableSet(canonical);
    }

    public static RecipeClientConfig defaults() {
        return new RecipeClientConfig(
                SCHEMA_VERSION,
                RecipeVisibilityPolicy.ALL_KNOWN,
                AUTO,
                Set.of());
    }
}
