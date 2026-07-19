package dev.tomewisp.client.gui.nativeview;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/** One Screen's visible native-view lifecycle and provider fallback owner. */
public final class NativeDomainViewRegistry implements AutoCloseable {
    private final Supplier<List<NativeDomainViewProvider>> optionalProviders;
    private final NativeDomainViewProvider fallback;
    private final BooleanSupplier clientThread;
    private final Map<String, Entry> active = new LinkedHashMap<>();
    private final Set<String> visible = new HashSet<>();
    private final Set<NativeDomainViewDiagnostic> diagnostics = new LinkedHashSet<>();

    public NativeDomainViewRegistry() {
        this(
                NativeDomainViewProviderRegistry::providers,
                new GenericRecipeNativeViewProvider(),
                () -> Minecraft.getInstance().isSameThread());
    }

    NativeDomainViewRegistry(
            Supplier<List<NativeDomainViewProvider>> optionalProviders,
            NativeDomainViewProvider fallback,
            BooleanSupplier clientThread) {
        this.optionalProviders = java.util.Objects.requireNonNull(optionalProviders, "optionalProviders");
        this.fallback = java.util.Objects.requireNonNull(fallback, "fallback");
        this.clientThread = java.util.Objects.requireNonNull(clientThread, "clientThread");
    }

    public void beginFrame() {
        requireClientThread();
        visible.clear();
    }

    public NativeDomainView resolve(NativeDomainViewBinding binding) {
        requireClientThread();
        java.util.Objects.requireNonNull(binding, "binding");
        visible.add(binding.stableId());
        Entry retained = active.get(binding.stableId());
        if (retained != null && retained.binding().equals(binding)) {
            return retained.view();
        }
        if (retained != null) {
            close(retained.view());
            active.remove(binding.stableId());
        }
        NativeDomainView created = create(binding);
        active.put(binding.stableId(), new Entry(binding, created));
        return created;
    }

    public boolean render(
            NativeDomainViewBinding binding, NativeDomainView.RenderContext context) {
        NativeDomainView view = resolve(binding);
        try {
            view.render(context);
            return true;
        } catch (LinkageError | RuntimeException failure) {
            diagnostics.add(new NativeDomainViewDiagnostic(
                    binding.stableId(), view.providerId(), "native_view_failed"));
            close(view);
            NativeDomainViewProvider.Attempt attempt = fallback.create(binding);
            if (!(attempt instanceof NativeDomainViewProvider.Attempt.Ready ready)) {
                active.remove(binding.stableId());
                return false;
            }
            active.put(binding.stableId(), new Entry(binding, ready.view()));
            try {
                ready.view().render(context);
                return true;
            } catch (RuntimeException fallbackFailure) {
                close(ready.view());
                active.remove(binding.stableId());
                return false;
            }
        }
    }

    public void endFrame() {
        requireClientThread();
        List<String> released = active.keySet().stream()
                .filter(id -> !visible.contains(id))
                .toList();
        released.forEach(id -> close(active.remove(id).view()));
    }

    public void tick() {
        requireClientThread();
        List.copyOf(active.values()).forEach(entry -> {
            try {
                entry.view().tick();
            } catch (RuntimeException failure) {
                diagnostics.add(new NativeDomainViewDiagnostic(
                        entry.binding().stableId(), entry.view().providerId(), "native_view_failed"));
                close(entry.view());
                active.remove(entry.binding().stableId());
            }
        });
    }

    public List<NativeDomainViewDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public int activeViewCount() {
        return active.size();
    }

    public void clear() {
        requireClientThread();
        active.values().forEach(entry -> close(entry.view()));
        active.clear();
        visible.clear();
    }

    @Override
    public void close() {
        clear();
    }

    private NativeDomainView create(NativeDomainViewBinding binding) {
        List<NativeDomainViewProvider> providers = new java.util.ArrayList<>(optionalProviders.get());
        providers.add(fallback);
        for (NativeDomainViewProvider provider : providers) {
            if (!provider.supports(binding)) continue;
            try {
                NativeDomainViewProvider.Attempt attempt = provider.create(binding);
                if (attempt instanceof NativeDomainViewProvider.Attempt.Ready ready) {
                    if (ready.view().family() != binding.family()) {
                        close(ready.view());
                        diagnostics.add(new NativeDomainViewDiagnostic(
                                binding.stableId(), provider.providerId(), "native_view_failed"));
                        continue;
                    }
                    return ready.view();
                }
                NativeDomainViewProvider.Attempt.Unsupported unsupported =
                        (NativeDomainViewProvider.Attempt.Unsupported) attempt;
                diagnostics.add(new NativeDomainViewDiagnostic(
                        binding.stableId(), provider.providerId(), unsupported.code()));
            } catch (LinkageError | RuntimeException failure) {
                diagnostics.add(new NativeDomainViewDiagnostic(
                        binding.stableId(), provider.providerId(), "native_view_failed"));
            }
        }
        throw new IllegalStateException("native view fallback did not resolve " + binding.family());
    }

    private void requireClientThread() {
        if (!clientThread.getAsBoolean()) {
            throw new IllegalStateException("native domain views require the Minecraft client thread");
        }
    }

    private static void close(NativeDomainView view) {
        try {
            view.close();
        } catch (RuntimeException ignored) {
            // The view is already detached from the registry; one provider cannot retain it.
        }
    }

    private record Entry(NativeDomainViewBinding binding, NativeDomainView view) {}
}
