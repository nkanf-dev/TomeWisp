package dev.tomewisp.client.gui.settings;

import dev.tomewisp.settings.tool.ToolSettingsView;
import dev.tomewisp.tool.config.ToolFamilyConfig;
import dev.tomewisp.tool.config.ToolFamilyId;
import dev.tomewisp.tool.config.ToolSourceDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Player-friendly Tools master-detail projection; technical IDs are debug-only text. */
public record ToolSettingsProjection(List<Family> families, boolean debugMode) {
    public ToolSettingsProjection {
        families = List.copyOf(families);
    }

    public static ToolSettingsProjection from(ToolSettingsView view, boolean debugMode) {
        Objects.requireNonNull(view, "view");
        return new ToolSettingsProjection(
                view.families().stream().map(Family::from).toList(), debugMode);
    }

    public Optional<Family> find(ToolFamilyId id) {
        return families.stream().filter(family -> family.id() == id).findFirst();
    }

    public ToolFamilyConfig toggleTool(ToolFamilyId id) {
        Family selected = find(id).orElseThrow();
        return selected.toConfig(!selected.enabled());
    }

    public ToolFamilyConfig toggleSource(ToolFamilyId id, String sourceId) {
        Family selected = find(id).orElseThrow();
        List<ToolSourceDefinition> sources = new ArrayList<>();
        boolean found = false;
        for (Source source : selected.sources()) {
            if (source.id().equals(sourceId)) {
                found = true;
                sources.add(source.toDefinition(!source.enabled()));
            } else {
                sources.add(source.toDefinition(source.enabled()));
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Unknown Tool source " + sourceId);
        }
        return new ToolFamilyConfig(
                ToolFamilyConfig.SCHEMA_VERSION, id, selected.enabled(), sources);
    }

    public record Family(
            ToolFamilyId id,
            String titleKey,
            String descriptionKey,
            boolean enabled,
            boolean available,
            List<Source> sources,
            ToolSettingsView.RecipeDetail recipes) {
        public Family {
            sources = List.copyOf(sources);
        }

        static Family from(ToolSettingsView.Family family) {
            return new Family(
                    family.id(),
                    family.titleKey(),
                    family.descriptionKey(),
                    family.enabled(),
                    family.available(),
                    family.sources().stream().map(Source::from).toList(),
                    family.recipeDetail());
        }

        ToolFamilyConfig toConfig(boolean replacementEnabled) {
            return new ToolFamilyConfig(
                    ToolFamilyConfig.SCHEMA_VERSION,
                    id,
                    replacementEnabled,
                    sources.stream()
                            .map(source -> source.toDefinition(source.enabled()))
                            .toList());
        }
    }

    public record Source(
            String id,
            String kind,
            String displayName,
            boolean enabled,
            boolean available,
            ToolSourceDefinition.Lifecycle lifecycle,
            boolean editable,
            boolean deletable,
            com.google.gson.JsonObject config) {
        public Source {
            config = config.deepCopy();
        }

        static Source from(ToolSettingsView.Source source) {
            return new Source(
                    source.id(),
                    source.kind(),
                    source.displayName(),
                    source.enabled(),
                    source.available(),
                    source.lifecycle(),
                    source.editable(),
                    source.deletable(),
                    source.config());
        }

        @Override
        public com.google.gson.JsonObject config() {
            return config.deepCopy();
        }

        ToolSourceDefinition toDefinition(boolean replacementEnabled) {
            return new ToolSourceDefinition(
                    id, kind, displayName, replacementEnabled, config, lifecycle);
        }
    }
}
