package dev.openallay.script.extension;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.ToolInvocationContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Isolated registry for trusted extension-provided detached JavaScript modules. */
public final class JavascriptDataModuleRegistry {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private final Map<String, RegisteredModule> modules = new TreeMap<>();

    public synchronized void register(
            String providerId, Collection<? extends JavascriptDataModule> additions) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Module provider ID must not be blank");
        }
        String normalizedProvider = providerId.strip();
        List<? extends JavascriptDataModule> candidates =
                List.copyOf(Objects.requireNonNull(additions, "additions"));
        Set<String> batchIds = new HashSet<>();
        for (JavascriptDataModule module : candidates) {
            Objects.requireNonNull(module, "module");
            String id = module.id();
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Invalid JavaScript module ID: " + id);
            }
            if (!batchIds.add(id)) {
                throw new IllegalStateException(
                        "Duplicate JavaScript module ID in provider "
                                + normalizedProvider + ": " + id);
            }
            RegisteredModule existing = modules.get(id);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate JavaScript module ID " + id
                                + " from providers " + existing.providerId()
                                + " and " + normalizedProvider);
            }
        }
        candidates.forEach(module ->
                modules.put(module.id(), new RegisteredModule(normalizedProvider, module)));
    }

    public Snapshot capture(ToolInvocationContext context) {
        List<RegisteredModule> captured;
        synchronized (this) {
            captured = List.copyOf(modules.values());
        }
        Map<String, Object> values = new TreeMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<EvidenceMetadata> evidence = new ArrayList<>();
        for (RegisteredModule registered : captured) {
            JavascriptDataModule module = registered.module();
            try {
                JavascriptDataModule.Snapshot snapshot = module.capture(context);
                values.put(module.id(), snapshot.value());
                evidence.addAll(snapshot.evidence());
            } catch (RuntimeException failure) {
                diagnostics.add(new Diagnostic(
                        module.id(), registered.providerId(), "module_capture_failed"));
            }
        }
        return new Snapshot(
                Map.copyOf(values),
                List.copyOf(diagnostics),
                evidence.stream().distinct().toList());
    }

    public record Diagnostic(String module, String provider, String code) {}

    public record Snapshot(
            Map<String, Object> values,
            List<Diagnostic> diagnostics,
            List<EvidenceMetadata> evidence) {
        public Snapshot {
            values = Map.copyOf(values);
            diagnostics = List.copyOf(diagnostics);
            evidence = List.copyOf(evidence);
        }
    }

    private record RegisteredModule(String providerId, JavascriptDataModule module) {}
}
