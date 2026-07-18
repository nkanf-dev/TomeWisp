package dev.tomewisp.settings.tool;

import com.google.gson.JsonObject;
import dev.tomewisp.capability.CapabilitySettingsEntry;
import dev.tomewisp.settings.capability.CapabilitySettingsView;
import dev.tomewisp.settings.capability.RecipeSettingsView;
import dev.tomewisp.tool.config.ToolFamilyConfig;
import dev.tomewisp.tool.config.ToolFamilyId;
import dev.tomewisp.tool.config.ToolSourceDefinition;
import dev.tomewisp.tool.config.ToolSourceKind;
import dev.tomewisp.tool.config.ToolSourceKindRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable, player-facing master-detail projection of the six logical Tool families. */
public record ToolSettingsView(List<Family> families) {
    public ToolSettingsView {
        families = List.copyOf(families);
        EnumMap<ToolFamilyId, Family> byId = new EnumMap<>(ToolFamilyId.class);
        for (Family family : families) {
            if (byId.put(family.id(), family) != null) {
                throw new IllegalArgumentException("Duplicate Tool family " + family.id());
            }
        }
        if (byId.size() != ToolFamilyId.values().length) {
            throw new IllegalArgumentException("All logical Tool families are required");
        }
    }

    public Optional<Family> find(ToolFamilyId id) {
        Objects.requireNonNull(id, "id");
        return families.stream().filter(family -> family.id() == id).findFirst();
    }

    public static ToolSettingsView empty() {
        List<Family> families = new ArrayList<>();
        for (ToolFamilyId id : ToolFamilyId.values()) {
            families.add(new Family(
                    id,
                    titleKey(id),
                    descriptionKey(id),
                    true,
                    false,
                    id.memberToolIds(),
                    List.of(),
                    id == ToolFamilyId.RECIPES
                            ? new RecipeDetail("ALL_KNOWN", "auto", true, List.of(), Set.of())
                            : null));
        }
        return new ToolSettingsView(families);
    }

    static ToolSettingsView project(
            Map<ToolFamilyId, ToolFamilyConfig> configs,
            ToolSourceKindRegistry registry,
            CapabilitySettingsView capabilities,
            RecipeSettingsView recipes) {
        Objects.requireNonNull(configs, "configs");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(recipes, "recipes");

        Map<String, CapabilitySettingsEntry> capabilityById = new LinkedHashMap<>();
        for (CapabilitySettingsEntry entry : capabilities.catalog().entries()) {
            capabilityById.put(entry.id(), entry);
        }
        Set<String> deniedTools = capabilities.policy().disabledTools();
        List<Family> projected = new ArrayList<>();
        for (ToolFamilyId id : ToolFamilyId.values()) {
            ToolFamilyConfig config = Objects.requireNonNull(
                    configs.get(id), "Missing Tool family " + id);
            List<CapabilitySettingsEntry> knownMembers = id.memberToolIds().stream()
                    .map(capabilityById::get)
                    .filter(Objects::nonNull)
                    .toList();
            boolean available = knownMembers.isEmpty()
                    || knownMembers.stream().anyMatch(CapabilitySettingsEntry::available);
            boolean policyEnabled = id.memberToolIds().stream()
                    .noneMatch(deniedTools::contains);
            List<Source> sources = config.sources().stream()
                    .map(source -> source(source, registry, recipes))
                    .toList();
            projected.add(new Family(
                    id,
                    titleKey(id),
                    descriptionKey(id),
                    config.enabled() && policyEnabled,
                    available,
                    id.memberToolIds(),
                    sources,
                    id == ToolFamilyId.RECIPES ? RecipeDetail.from(recipes) : null));
        }
        return new ToolSettingsView(projected);
    }

    private static Source source(
            ToolSourceDefinition definition,
            ToolSourceKindRegistry registry,
            RecipeSettingsView recipes) {
        ToolSourceKind kind = registry.find(definition.sourceKind()).orElseThrow(() ->
                new IllegalArgumentException("Unregistered source kind " + definition.sourceKind()));
        boolean builtIn = definition.lifecycle() == ToolSourceDefinition.Lifecycle.BUILT_IN;
        boolean available = recipes.sources().stream()
                .filter(source -> source.id().equals(definition.sourceId()))
                .map(RecipeSettingsView.Source::available)
                .findFirst()
                .orElse(true);
        return new Source(
                definition.sourceId(),
                definition.sourceKind(),
                definition.displayName(),
                definition.enabled(),
                available,
                definition.lifecycle(),
                !builtIn,
                !builtIn,
                definition.config(),
                kind.editorFields());
    }

    private static String titleKey(ToolFamilyId id) {
        return "tomewisp.settings.tools." + keySegment(id) + ".title";
    }

    private static String descriptionKey(ToolFamilyId id) {
        return "tomewisp.settings.tools." + keySegment(id) + ".description";
    }

    private static String keySegment(ToolFamilyId id) {
        return id.serializedId().substring(id.serializedId().indexOf(':') + 1);
    }

    public record Family(
            ToolFamilyId id,
            String titleKey,
            String descriptionKey,
            boolean enabled,
            boolean available,
            List<String> memberToolIds,
            List<Source> sources,
            RecipeDetail recipeDetail) {
        public Family {
            Objects.requireNonNull(id, "id");
            if (titleKey == null || titleKey.isBlank()
                    || descriptionKey == null || descriptionKey.isBlank()) {
                throw new IllegalArgumentException("Tool title and description keys are required");
            }
            memberToolIds = List.copyOf(memberToolIds);
            sources = List.copyOf(sources);
            if (id == ToolFamilyId.RECIPES && recipeDetail == null) {
                throw new IllegalArgumentException("Recipes Tool requires recipe detail");
            }
            if (id != ToolFamilyId.RECIPES && recipeDetail != null) {
                throw new IllegalArgumentException("Only Recipes may expose recipe detail");
            }
        }

        public Optional<RecipeDetail> recipes() {
            return Optional.ofNullable(recipeDetail);
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
            JsonObject config,
            List<ToolSourceKind.EditorField> editorFields) {
        public Source {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(config, "config");
            if (lifecycle == ToolSourceDefinition.Lifecycle.BUILT_IN && (editable || deletable)) {
                throw new IllegalArgumentException("Built-in sources are read-only identities");
            }
            config = config.deepCopy();
            editorFields = List.copyOf(editorFields);
        }

        @Override
        public JsonObject config() {
            return config.deepCopy();
        }
    }

    /** Read-only projection of the existing recipe options and discovered viewer capabilities. */
    public record RecipeDetail(
            String visibility,
            String preferredViewer,
            boolean preferredViewerAvailable,
            List<RecipeSource> discoveredSources,
            Set<String> unknownDisabledSources) {
        public RecipeDetail {
            if (visibility == null || visibility.isBlank()
                    || preferredViewer == null || preferredViewer.isBlank()) {
                throw new IllegalArgumentException("Recipe visibility and preference are required");
            }
            discoveredSources = List.copyOf(discoveredSources);
            unknownDisabledSources = Collections.unmodifiableSet(
                    new java.util.TreeSet<>(unknownDisabledSources));
        }

        static RecipeDetail from(RecipeSettingsView view) {
            return new RecipeDetail(
                    view.config().visibility().name(),
                    view.config().preferredViewer(),
                    view.preferredViewerAvailable(),
                    view.sources().stream().map(RecipeSource::from).toList(),
                    view.unknownDisabledSources());
        }
    }

    public record RecipeSource(
            String id,
            boolean available,
            boolean enabled,
            boolean viewer,
            boolean exactNavigation) {
        static RecipeSource from(RecipeSettingsView.Source source) {
            return new RecipeSource(
                    source.id(),
                    source.available(),
                    source.enabled(),
                    source.viewer(),
                    source.exactNavigation());
        }
    }
}
