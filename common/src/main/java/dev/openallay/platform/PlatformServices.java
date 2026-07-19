package dev.openallay.platform;

import java.util.List;
import java.util.ServiceLoader;

public final class PlatformServices {
    private PlatformServices() {}

    public static PlatformService load() {
        List<PlatformService> services = ServiceLoader.load(
                        PlatformService.class, PlatformServices.class.getClassLoader())
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        if (services.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one PlatformService, found " + services.size());
        }
        return services.getFirst();
    }
}
