package dev.tomewisp.client.gui.nativeview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Process registry populated by optional integration entrypoints only when their APIs exist. */
public final class NativeDomainViewProviderRegistry {
    private static final Map<String, NativeDomainViewProvider> PROVIDERS = new TreeMap<>();

    private NativeDomainViewProviderRegistry() {}

    public static synchronized void register(NativeDomainViewProvider provider) {
        java.util.Objects.requireNonNull(provider, "provider");
        if (provider.providerId().isBlank()) {
            throw new IllegalArgumentException("native provider ID is required");
        }
        PROVIDERS.put(provider.providerId(), provider);
    }

    public static synchronized List<NativeDomainViewProvider> providers() {
        ArrayList<NativeDomainViewProvider> result = new ArrayList<>(PROVIDERS.values());
        result.sort(Comparator.comparingInt(NativeDomainViewProvider::priority)
                .reversed()
                .thenComparing(NativeDomainViewProvider::providerId));
        return List.copyOf(result);
    }
}
