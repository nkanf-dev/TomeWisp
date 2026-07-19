package dev.tomewisp.settings.tool;

import dev.tomewisp.capability.CapabilityPolicy;
import dev.tomewisp.settings.capability.CapabilitySettingsView;
import dev.tomewisp.settings.capability.RecipeSettingsView;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.tool.RegisteredTool;
import dev.tomewisp.tool.config.ToolFamilyConfig;
import dev.tomewisp.tool.config.ToolFamilyId;
import dev.tomewisp.tool.config.ToolFamilySettingsStore;
import dev.tomewisp.tool.config.ToolSourceDefinition;
import dev.tomewisp.tool.config.ToolSourceKindRegistry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Coordinates complete candidates for the independently persisted logical Tool families. */
public final class ToolSettingsBackend {
    private final Map<ToolFamilyId, ToolFamilySettingsStore> stores;
    private final ToolSourceKindRegistry registry;
    private final Supplier<CapabilitySettingsView> capabilities;
    private final Supplier<RecipeSettingsView> recipes;
    private final List<RegisteredTool> registrations;

    public ToolSettingsBackend(
            Map<ToolFamilyId, ToolFamilySettingsStore> stores,
            ToolSourceKindRegistry registry,
            Supplier<CapabilitySettingsView> capabilities,
            Supplier<RecipeSettingsView> recipes) {
        this(stores, registry, capabilities, recipes, List.of());
    }

    public ToolSettingsBackend(
            Map<ToolFamilyId, ToolFamilySettingsStore> stores,
            ToolSourceKindRegistry registry,
            Supplier<CapabilitySettingsView> capabilities,
            Supplier<RecipeSettingsView> recipes,
            List<RegisteredTool> registrations) {
        Objects.requireNonNull(stores, "stores");
        EnumMap<ToolFamilyId, ToolFamilySettingsStore> copy = new EnumMap<>(ToolFamilyId.class);
        copy.putAll(stores);
        for (ToolFamilyId id : ToolFamilyId.values()) {
            ToolFamilySettingsStore store = copy.get(id);
            if (store == null) {
                throw new IllegalArgumentException("Missing settings store for " + id);
            }
            if (store.current().toolId() != id) {
                throw new IllegalArgumentException("Settings store belongs to another Tool family");
            }
        }
        if (copy.size() != ToolFamilyId.values().length) {
            throw new IllegalArgumentException("Unexpected Tool-family settings store");
        }
        this.stores = Map.copyOf(copy);
        this.registry = Objects.requireNonNull(registry, "registry");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.recipes = Objects.requireNonNull(recipes, "recipes");
        this.registrations = List.copyOf(registrations);
    }

    public ToolSettingsView currentView() {
        return ToolSettingsView.project(
                currentConfigs(), registry, capabilities.get(), recipes.get(), registrations);
    }

    public State currentState() {
        CapabilityPolicy candidate = capabilities.get().policy();
        for (ToolFamilyId id : ToolFamilyId.values()) {
            candidate = capabilityPolicyCandidate(candidate, id, current(id).enabled());
        }
        return stateWithPolicy(candidate);
    }

    public ToolFamilyConfig current(ToolFamilyId id) {
        return store(id).current();
    }

    public ToolResult<State> reload(ToolFamilyId id) {
        ToolResult<ToolFamilyConfig> loaded = store(id).load();
        if (loaded instanceof ToolResult.Failure<ToolFamilyConfig> failure) {
            return failure(failure);
        }
        return new ToolResult.Success<>(stateFor(id, store(id).current().enabled()));
    }

    public ToolResult<State> save(ToolFamilyConfig candidate) {
        return save(candidate, true);
    }

    private ToolResult<State> saveSourceCandidate(ToolFamilyConfig candidate) {
        return save(candidate, false);
    }

    private ToolResult<State> save(ToolFamilyConfig candidate, boolean synchronizeFamilyPolicy) {
        Objects.requireNonNull(candidate, "candidate");
        ToolResult<ToolFamilyConfig> saved = store(candidate.toolId()).save(candidate);
        if (saved instanceof ToolResult.Failure<ToolFamilyConfig> failure) {
            return failure(failure);
        }
        CapabilityPolicy policy = synchronizeFamilyPolicy
                ? capabilityPolicyCandidate(candidate.toolId(), candidate.enabled())
                : capabilities.get().policy();
        return new ToolResult.Success<>(stateWithPolicy(policy));
    }

    public ToolResult<State> setToolEnabled(ToolFamilyId id, boolean enabled) {
        ToolFamilyConfig current = current(id);
        return save(new ToolFamilyConfig(
                ToolFamilyConfig.SCHEMA_VERSION, id, enabled, current.sources()));
    }

    public ToolResult<State> setSourceEnabled(
            ToolFamilyId id, String sourceId, boolean enabled) {
        ToolFamilyConfig current = current(id);
        List<ToolSourceDefinition> replacement = new ArrayList<>(current.sources().size());
        boolean found = false;
        for (ToolSourceDefinition source : current.sources()) {
            if (!source.sourceId().equals(sourceId)) {
                replacement.add(source);
                continue;
            }
            found = true;
            replacement.add(copy(source, source.displayName(), enabled, source.config(), source.sourceKind()));
        }
        if (!found) {
            return failure("source_not_found", "Unknown Tool source " + sourceId);
        }
        return saveSourceCandidate(new ToolFamilyConfig(
                ToolFamilyConfig.SCHEMA_VERSION, id, current.enabled(), replacement));
    }

    public ToolResult<State> createSource(
            ToolFamilyId id, ToolSourceDefinition source) {
        Objects.requireNonNull(source, "source");
        if (source.lifecycle() != ToolSourceDefinition.Lifecycle.USER) {
            return failure("user_source_required", "Only user sources can be created");
        }
        ToolFamilyConfig current = current(id);
        if (current.sources().stream().anyMatch(existing -> existing.sourceId().equals(source.sourceId()))) {
            return failure("source_already_exists", "Tool source already exists " + source.sourceId());
        }
        List<ToolSourceDefinition> replacement = new ArrayList<>(current.sources());
        replacement.add(source);
        return saveSourceCandidate(new ToolFamilyConfig(
                ToolFamilyConfig.SCHEMA_VERSION, id, current.enabled(), replacement));
    }

    public ToolResult<State> updateSource(
            ToolFamilyId id, String sourceId, ToolSourceDefinition replacementSource) {
        Objects.requireNonNull(replacementSource, "replacementSource");
        if (!replacementSource.sourceId().equals(sourceId)) {
            return failure("source_identity_immutable", "Source identity cannot change during edit");
        }
        ToolFamilyConfig current = current(id);
        List<ToolSourceDefinition> replacement = new ArrayList<>(current.sources().size());
        boolean found = false;
        for (ToolSourceDefinition source : current.sources()) {
            if (!source.sourceId().equals(sourceId)) {
                replacement.add(source);
                continue;
            }
            found = true;
            if (source.lifecycle() == ToolSourceDefinition.Lifecycle.BUILT_IN) {
                return failure("builtin_source_immutable", "Built-in source identity cannot be edited");
            }
            if (replacementSource.lifecycle() != ToolSourceDefinition.Lifecycle.USER) {
                return failure("user_source_required", "A user source must remain user-owned");
            }
            replacement.add(replacementSource);
        }
        if (!found) {
            return failure("source_not_found", "Unknown Tool source " + sourceId);
        }
        return saveSourceCandidate(new ToolFamilyConfig(
                ToolFamilyConfig.SCHEMA_VERSION, id, current.enabled(), replacement));
    }

    public ToolResult<State> deleteSource(ToolFamilyId id, String sourceId) {
        ToolFamilyConfig current = current(id);
        List<ToolSourceDefinition> replacement = new ArrayList<>(current.sources().size());
        boolean found = false;
        for (ToolSourceDefinition source : current.sources()) {
            if (!source.sourceId().equals(sourceId)) {
                replacement.add(source);
                continue;
            }
            found = true;
            if (source.lifecycle() == ToolSourceDefinition.Lifecycle.BUILT_IN) {
                return failure("builtin_source_required", "Built-in sources cannot be deleted");
            }
        }
        if (!found) {
            return failure("source_not_found", "Unknown Tool source " + sourceId);
        }
        return saveSourceCandidate(new ToolFamilyConfig(
                ToolFamilyConfig.SCHEMA_VERSION, id, current.enabled(), replacement));
    }

    public ToolResult<State> restoreDefaults(ToolFamilyId id) {
        return save(store(id).defaults());
    }

    /** Builds the deny-only policy candidate that corresponds to a family enablement change. */
    public CapabilityPolicy capabilityPolicyCandidate(ToolFamilyId id, boolean enabled) {
        Objects.requireNonNull(id, "id");
        return capabilityPolicyCandidate(capabilities.get().policy(), id, enabled);
    }

    private static CapabilityPolicy capabilityPolicyCandidate(
            CapabilityPolicy current, ToolFamilyId id, boolean enabled) {
        Set<String> disabledTools = new HashSet<>(current.disabledTools());
        if (enabled) {
            disabledTools.removeAll(id.memberToolIds());
        } else {
            disabledTools.addAll(id.memberToolIds());
        }
        return new CapabilityPolicy(
                CapabilityPolicy.SCHEMA_VERSION, disabledTools, current.disabledSkills());
    }

    public List<ToolSourceKindView> creatableSourceKinds(ToolFamilyId id) {
        return registry.kinds(id).stream()
                .filter(kind -> kind.userCreatable()
                        && kind.lifecycles().contains(ToolSourceDefinition.Lifecycle.USER))
                .map(kind -> new ToolSourceKindView(
                        kind.sourceKind(), kind.editorFields(), kind.credentialReferenceAllowed()))
                .toList();
    }

    private Map<ToolFamilyId, ToolFamilyConfig> currentConfigs() {
        EnumMap<ToolFamilyId, ToolFamilyConfig> configs = new EnumMap<>(ToolFamilyId.class);
        for (ToolFamilyId id : ToolFamilyId.values()) {
            configs.put(id, current(id));
        }
        return configs;
    }

    private State stateFor(ToolFamilyId changedFamily, boolean enabled) {
        CapabilityPolicy candidate = capabilityPolicyCandidate(changedFamily, enabled);
        return stateWithPolicy(candidate);
    }

    private State stateWithPolicy(CapabilityPolicy candidate) {
        CapabilitySettingsView current = capabilities.get();
        CapabilitySettingsView projectedCapabilities = new CapabilitySettingsView(
                candidate,
                current.catalog(),
                current.unknownDisabledTools(),
                current.unknownDisabledSkills());
        return new State(
                ToolSettingsView.project(
                        currentConfigs(), registry, projectedCapabilities, recipes.get(), registrations),
                candidate);
    }

    private ToolFamilySettingsStore store(ToolFamilyId id) {
        Objects.requireNonNull(id, "id");
        return stores.get(id);
    }

    private static ToolSourceDefinition copy(
            ToolSourceDefinition source,
            String displayName,
            boolean enabled,
            com.google.gson.JsonObject config,
            String sourceKind) {
        return new ToolSourceDefinition(
                source.sourceId(),
                sourceKind,
                displayName,
                enabled,
                config,
                source.lifecycle());
    }

    private static ToolResult<State> failure(ToolResult.Failure<?> failure) {
        return failure(failure.code(), failure.message());
    }

    private static ToolResult<State> failure(String code, String message) {
        return new ToolResult.Failure<>(code, message);
    }

    /** Persisted Tool projection plus the complete deny-policy candidate for runtime publication. */
    public record State(ToolSettingsView view, CapabilityPolicy capabilityPolicy) {
        public State {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(capabilityPolicy, "capabilityPolicy");
        }
    }

    public record ToolSourceKindView(
            String kind,
            List<dev.tomewisp.tool.config.ToolSourceKind.EditorField> editorFields,
            boolean credentialReferenceAllowed) {
        public ToolSourceKindView {
            Objects.requireNonNull(kind, "kind");
            editorFields = List.copyOf(editorFields);
        }
    }
}
