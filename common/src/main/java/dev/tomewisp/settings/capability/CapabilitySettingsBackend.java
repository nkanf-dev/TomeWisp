package dev.tomewisp.settings.capability;

import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.capability.CapabilityCatalogState;
import dev.tomewisp.capability.CapabilityPolicy;
import dev.tomewisp.capability.CapabilityPolicyStore;
import dev.tomewisp.capability.ClientCapabilityResolver;
import dev.tomewisp.capability.ClientCapabilitySnapshot;
import dev.tomewisp.client.ClientModelRuntimeRegistry;
import dev.tomewisp.settings.ClientSettingsService;
import dev.tomewisp.tool.ToolResult;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/** Resolves dependency closure before atomic policy persistence and runtime publication. */
public final class CapabilitySettingsBackend implements ClientSettingsService.CapabilityActions {
    private final TomeWispRuntime product;
    private final CapabilityPolicyStore store;
    private final ClientCapabilityResolver resolver = new ClientCapabilityResolver();
    private final Consumer<ClientCapabilitySnapshot> publish;
    private volatile ClientCapabilitySnapshot current;

    public CapabilitySettingsBackend(
            Path path,
            TomeWispRuntime product,
            ClientModelRuntimeRegistry models) {
        this(path, product, models.capabilities(), models::replaceCapabilities);
    }

    CapabilitySettingsBackend(
            Path path,
            TomeWispRuntime product,
            ClientCapabilitySnapshot initial,
            Consumer<ClientCapabilitySnapshot> publish) {
        this.product = Objects.requireNonNull(product, "product");
        this.store = new CapabilityPolicyStore(path);
        this.current = Objects.requireNonNull(initial, "initial");
        this.publish = Objects.requireNonNull(publish, "publish");
    }

    public CapabilitySettingsView currentView() {
        return view(current.policy());
    }

    @Override
    public ToolResult<CapabilitySettingsView> saveCapabilities(CapabilityPolicy candidate) {
        ToolResult<ClientCapabilitySnapshot> resolved = resolve(candidate);
        if (resolved instanceof ToolResult.Failure<ClientCapabilitySnapshot> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        ClientCapabilitySnapshot prepared =
                ((ToolResult.Success<ClientCapabilitySnapshot>) resolved).value();
        ToolResult<CapabilityPolicy> saved = store.save(candidate);
        if (saved instanceof ToolResult.Failure<CapabilityPolicy> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        publish.accept(prepared);
        current = prepared;
        return new ToolResult.Success<>(view(candidate));
    }

    /**
     * Publishes a policy reconstructed from Tool-family files without writing the legacy generic
     * capability document. Tool settings remain the durable source of truth.
     */
    public ToolResult<CapabilitySettingsView> publishCapabilities(CapabilityPolicy candidate) {
        CapabilityPolicy normalized = toolOwnedPolicy(candidate);
        ToolResult<ClientCapabilitySnapshot> resolved = resolve(normalized);
        if (resolved instanceof ToolResult.Failure<ClientCapabilitySnapshot> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        ClientCapabilitySnapshot prepared =
                ((ToolResult.Success<ClientCapabilitySnapshot>) resolved).value();
        publish.accept(prepared);
        current = prepared;
        return new ToolResult.Success<>(view(normalized));
    }

    private CapabilityPolicy toolOwnedPolicy(CapabilityPolicy candidate) {
        Set<String> knownSkills = product.skills().metadata().stream()
                .map(metadata -> metadata.name())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> disabledSkills = new TreeSet<>(candidate.disabledSkills());
        disabledSkills.removeAll(knownSkills);
        product.skills().metadata().stream()
                .filter(metadata -> metadata.allowedTools().stream()
                        .anyMatch(candidate.disabledTools()::contains))
                .map(metadata -> metadata.name())
                .forEach(disabledSkills::add);
        return new CapabilityPolicy(
                CapabilityPolicy.SCHEMA_VERSION,
                candidate.disabledTools(),
                disabledSkills);
    }

    @Override
    public ToolResult<CapabilitySettingsView> reloadCapabilities() {
        ToolResult<CapabilityPolicy> loaded = store.load();
        if (loaded instanceof ToolResult.Failure<CapabilityPolicy> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        CapabilityPolicy policy = ((ToolResult.Success<CapabilityPolicy>) loaded).value();
        ToolResult<ClientCapabilitySnapshot> resolved = resolve(policy);
        if (resolved instanceof ToolResult.Failure<ClientCapabilitySnapshot> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        ClientCapabilitySnapshot prepared =
                ((ToolResult.Success<ClientCapabilitySnapshot>) resolved).value();
        publish.accept(prepared);
        current = prepared;
        return new ToolResult.Success<>(view(policy));
    }

    private ToolResult<ClientCapabilitySnapshot> resolve(CapabilityPolicy policy) {
        return resolver.resolve(policy, product.tools().registrations(), product.skills());
    }

    private CapabilitySettingsView view(CapabilityPolicy policy) {
        Set<String> knownTools = product.tools().descriptors().stream()
                .map(descriptor -> descriptor.id())
                .filter(id -> !id.equals(ClientCapabilityResolver.LOAD_SKILL_ID))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> knownSkills = product.skills().metadata().stream()
                .map(metadata -> metadata.name())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> unknownTools = new TreeSet<>(policy.disabledTools());
        unknownTools.removeAll(knownTools);
        Set<String> unknownSkills = new TreeSet<>(policy.disabledSkills());
        unknownSkills.removeAll(knownSkills);
        Set<String> disabled = new HashSet<>(policy.disabledTools());
        disabled.addAll(policy.disabledSkills());
        Set<String> unavailable = product.platform().isModLoaded("ftbquests")
                ? Set.of()
                : Set.of("ftbquests");
        return new CapabilitySettingsView(
                policy,
                product.capabilitySettings().snapshot(
                        new CapabilityCatalogState(unavailable, disabled)),
                unknownTools,
                unknownSkills);
    }
}
