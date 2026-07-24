package dev.openallay.script.data;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.RegistryEntrySnapshot;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.knowledge.KnowledgeSnapshot;
import dev.openallay.script.JavascriptExecutionException;
import dev.openallay.script.extension.JavascriptDataModuleRegistry;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Request-scoped root graph over the original detached Java snapshots.
 *
 * <p>Root values are resolved only when selected and then retained for the request. No Gson tree,
 * source literal, or deep copy is created on the input path.
 */
public final class MinecraftAgentHostGraph {
    private static final java.util.regex.Pattern ROOT =
            java.util.regex.Pattern.compile("[a-zA-Z][a-zA-Z0-9]*");

    private final Map<String, MemoizedSupplier> roots;
    private final LinkedHashSet<EvidenceMetadata> evidence = new LinkedHashSet<>();

    public MinecraftAgentHostGraph(ToolInvocationContext context) {
        this(context, KnowledgeSnapshot::empty, new JavascriptDataModuleRegistry());
    }

    public MinecraftAgentHostGraph(
            ToolInvocationContext context, Supplier<KnowledgeSnapshot> knowledge) {
        this(context, knowledge, new JavascriptDataModuleRegistry());
    }

    public MinecraftAgentHostGraph(
            ToolInvocationContext context,
            Supplier<KnowledgeSnapshot> knowledge,
            JavascriptDataModuleRegistry extensions) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(knowledge, "knowledge");
        Objects.requireNonNull(extensions, "extensions");

        LinkedHashMap<String, MemoizedSupplier> available = new LinkedHashMap<>();
        add(available, "caller", context::caller);
        add(available, "metrics", context::metrics);
        add(available, "capturedAt", context::capturedAt);

        context.player().ifPresent(player -> {
            add(available, "player", () -> player);
            addEvidence(player.evidence());
        });
        context.registries().ifPresent(registries -> {
            add(available, "registries", () ->
                    new RegistryCatalog(registries.evidence(), registries.entries().size()));
            addEvidence(registries.evidence());
            Map<String, List<RegistryEntrySnapshot>> grouped = group(registries.entries());
            grouped.forEach((name, values) -> add(available, name, () -> values));
        });
        context.recipes().ifPresent(recipes -> {
            add(available, "recipeCatalog", () ->
                    new RecipeCatalog(recipes.evidence(), recipes.recipes().size()));
            add(available, "recipes", recipes::recipes);
            addEvidence(recipes.evidence());
        });
        context.observableGameState().ifPresent(game -> {
            add(available, "game", () -> game);
            addEvidence(game.runtime().evidence());
            addEvidence(game.mods().evidence());
            addEvidence(game.options().evidence());
            addEvidence(game.packs().evidence());
            addEvidence(game.shaders().evidence());
            addEvidence(game.diagnostics().evidence());
            addEvidence(game.player().evidence());
            addEvidence(game.worldQueries().evidence());
        });

        add(available, "knowledge", () -> {
            KnowledgeSnapshot snapshot = Objects.requireNonNull(
                    knowledge.get(), "knowledge snapshot");
            addEvidence(snapshot.evidence());
            return snapshot.documents();
        });
        MemoizedSupplier extensionSnapshot = new MemoizedSupplier(() -> extensions.capture(context));
        add(available, "extensions", () -> {
            JavascriptDataModuleRegistry.Snapshot snapshot =
                    (JavascriptDataModuleRegistry.Snapshot) extensionSnapshot.get();
            addEvidence(snapshot.evidence());
            return snapshot.values();
        });
        add(available, "extensionDiagnostics", () ->
                extensionDiagnostics(extensionSnapshot));
        add(available, "capabilities", () -> List.copyOf(available.keySet()));
        add(available, "evidence", this::evidence);
        roots = Collections.unmodifiableMap(available);
    }

    /**
     * Returns an immutable lazy map. Empty selection exposes all descriptors, while explicit
     * selection resolves only requested roots plus stable discovery metadata.
     */
    public Map<String, Object> select(Collection<String> requested) {
        Collection<String> selection = requested == null ? List.of() : List.copyOf(requested);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (selection.isEmpty()) {
            names.addAll(roots.keySet());
        } else {
            for (String root : selection) {
                if (root == null || !ROOT.matcher(root).matches() || !roots.containsKey(root)) {
                    throw new JavascriptExecutionException(
                            "javascript_root_unavailable",
                            "Requested Minecraft data root is unavailable: " + root);
                }
                names.add(root);
            }
            if (roots.containsKey("capturedAt")) names.add("capturedAt");
            if (roots.containsKey("capabilities")) names.add("capabilities");
        }
        return new LazyRootMap(roots, List.copyOf(names));
    }

    public synchronized List<EvidenceMetadata> evidence() {
        return List.copyOf(evidence);
    }

    private synchronized void addEvidence(Collection<EvidenceMetadata> additions) {
        evidence.addAll(additions);
    }

    private synchronized void addEvidence(EvidenceMetadata addition) {
        evidence.add(addition);
    }

    private List<JavascriptDataModuleRegistry.Diagnostic> extensionDiagnostics(
            MemoizedSupplier extensionSnapshot) {
        JavascriptDataModuleRegistry.Snapshot snapshot =
                (JavascriptDataModuleRegistry.Snapshot) extensionSnapshot.get();
        addEvidence(snapshot.evidence());
        return snapshot.diagnostics();
    }

    private static void add(
            Map<String, MemoizedSupplier> roots, String name, Supplier<?> supplier) {
        roots.put(name, new MemoizedSupplier(supplier));
    }

    private static Map<String, List<RegistryEntrySnapshot>> group(
            List<RegistryEntrySnapshot> entries) {
        LinkedHashMap<String, ArrayList<RegistryEntrySnapshot>> mutable = new LinkedHashMap<>();
        for (RegistryEntrySnapshot entry : entries) {
            mutable.computeIfAbsent(
                    pluralize(entry.kind().toLowerCase(Locale.ROOT)),
                    ignored -> new ArrayList<>()).add(entry);
        }
        LinkedHashMap<String, List<RegistryEntrySnapshot>> result = new LinkedHashMap<>();
        mutable.forEach((name, values) -> result.put(name, List.copyOf(values)));
        return Collections.unmodifiableMap(result);
    }

    private static String pluralize(String kind) {
        return switch (kind) {
            case "item" -> "items";
            case "block" -> "blocks";
            case "fluid" -> "fluids";
            case "effect", "mob_effect" -> "effects";
            case "enchantment" -> "enchantments";
            case "entity", "entity_type" -> "entities";
            default -> kind.endsWith("s") ? kind : kind + "s";
        };
    }

    public record RegistryCatalog(EvidenceMetadata evidence, int entryCount) {}

    public record RecipeCatalog(EvidenceMetadata evidence, int recipeCount) {}

    private static final class MemoizedSupplier implements Supplier<Object> {
        private Supplier<?> source;
        private Object value;
        private boolean resolved;

        private MemoizedSupplier(Supplier<?> source) {
            this.source = Objects.requireNonNull(source, "source");
        }

        @Override
        public synchronized Object get() {
            if (!resolved) {
                value = Objects.requireNonNull(source.get(), "host root value");
                source = null;
                resolved = true;
            }
            return value;
        }
    }

    private static final class LazyRootMap extends AbstractMap<String, Object> {
        private final Map<String, MemoizedSupplier> roots;
        private final List<String> names;
        private final Set<Entry<String, Object>> entries;

        private LazyRootMap(Map<String, MemoizedSupplier> roots, List<String> names) {
            this.roots = roots;
            this.names = names;
            entries = new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, Object>> iterator() {
                    return names.stream()
                            .<Entry<String, Object>>map(name -> new Entry<>() {
                                @Override
                                public String getKey() {
                                    return name;
                                }

                                @Override
                                public Object getValue() {
                                    return roots.get(name).get();
                                }

                                @Override
                                public Object setValue(Object value) {
                                    throw new UnsupportedOperationException("read-only root graph");
                                }
                            })
                            .iterator();
                }

                @Override public int size() { return names.size(); }
            };
        }

        @Override
        public boolean containsKey(Object key) {
            return key instanceof String name && names.contains(name);
        }

        @Override
        public Object get(Object key) {
            return containsKey(key) ? roots.get(key).get() : null;
        }

        @Override
        public Set<String> keySet() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(names));
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return entries;
        }
    }
}
