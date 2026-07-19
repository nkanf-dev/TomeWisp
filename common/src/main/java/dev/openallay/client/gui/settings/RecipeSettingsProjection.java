package dev.openallay.client.gui.settings;

import dev.openallay.recipe.RecipeVisibilityPolicy;
import dev.openallay.recipe.config.RecipeClientConfig;
import dev.openallay.settings.capability.RecipeSettingsView;
import dev.openallay.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Pure recipe Tool child-page projection and current-schema draft edits. */
public record RecipeSettingsProjection(
        List<SourceRow> sources,
        RecipeClientConfig config,
        boolean preferredViewerAvailable,
        int retainedUnknownCount) {
    public RecipeSettingsProjection {
        sources = List.copyOf(sources);
        Objects.requireNonNull(config, "config");
        if (retainedUnknownCount < 0) {
            throw new IllegalArgumentException("retainedUnknownCount must not be negative");
        }
    }

    public static RecipeSettingsProjection from(
            RecipeSettingsView view, RecipeClientConfig draft, boolean debugMode) {
        List<SourceRow> rows = view.sources().stream()
                .map(source -> new SourceRow(
                        source.id(),
                        sourceTitleKey(source.id()),
                        source.available(),
                        !draft.disabledSources().contains(source.id()),
                        source.viewer(),
                        source.exactNavigation(),
                        debugMode ? source.id() : null))
                .toList();
        boolean preferredAvailable = RecipeClientConfig.AUTO.equals(draft.preferredViewer())
                || rows.stream().anyMatch(row -> row.viewer()
                        && row.available()
                        && row.enabled()
                        && row.actionId().equals(draft.preferredViewer()));
        return new RecipeSettingsProjection(
                rows,
                draft,
                preferredAvailable,
                view.unknownDisabledSources().size());
    }

    public ToolResult<RecipeClientConfig> toggleSource(String actionId) {
        if (sources.stream().noneMatch(source -> source.actionId().equals(actionId))) {
            return new ToolResult.Failure<>(
                    "recipe_source_unavailable", "This recipe source is unavailable");
        }
        Set<String> disabled = new TreeSet<>(config.disabledSources());
        if (!disabled.remove(actionId)) {
            disabled.add(actionId);
        }
        return new ToolResult.Success<>(new RecipeClientConfig(
                RecipeClientConfig.SCHEMA_VERSION,
                config.visibility(),
                config.preferredViewer(),
                disabled));
    }

    public RecipeClientConfig cycleVisibility() {
        RecipeVisibilityPolicy next = config.visibility() == RecipeVisibilityPolicy.ALL_KNOWN
                ? RecipeVisibilityPolicy.UNLOCKED_ONLY
                : RecipeVisibilityPolicy.ALL_KNOWN;
        return new RecipeClientConfig(
                RecipeClientConfig.SCHEMA_VERSION,
                next,
                config.preferredViewer(),
                config.disabledSources());
    }

    public RecipeClientConfig cyclePreferredViewer() {
        List<String> options = new ArrayList<>();
        options.add(RecipeClientConfig.AUTO);
        sources.stream()
                .filter(source -> source.viewer() && source.available() && source.enabled())
                .map(SourceRow::actionId)
                .sorted()
                .forEach(options::add);
        if (!options.contains(config.preferredViewer())) {
            options.add(config.preferredViewer());
        }
        int index = options.indexOf(config.preferredViewer());
        String next = options.get((index + 1) % options.size());
        return new RecipeClientConfig(
                RecipeClientConfig.SCHEMA_VERSION,
                config.visibility(),
                next,
                config.disabledSources());
    }

    private static String sourceTitleKey(String sourceId) {
        String key = sourceId.replace(':', '_').replace('/', '_').replace('-', '_');
        return "screen.openallay.settings.recipe.source." + key;
    }

    public record SourceRow(
            String actionId,
            String titleKey,
            boolean available,
            boolean enabled,
            boolean viewer,
            boolean exactNavigation,
            String debugId) {}
}
