package dev.openallay.script.host;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Cached, component-only view of a Java record. No methods are exposed to scripts. */
final class HostRecordSchema {
    private static final ConcurrentHashMap<Class<?>, HostRecordSchema> CACHE =
            new ConcurrentHashMap<>();

    private final List<String> names;
    private final Map<String, MethodHandle> accessors;

    private HostRecordSchema(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Host record schema requires a record type");
        }
        LinkedHashMap<String, MethodHandle> resolved = new LinkedHashMap<>();
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
            for (RecordComponent component : type.getRecordComponents()) {
                resolved.put(component.getName(), lookup.unreflect(component.getAccessor()));
            }
        } catch (IllegalAccessException failure) {
            throw new HostAccessException(
                    "javascript_host_type_unsupported",
                    "Detached record components are not accessible: " + type.getName());
        }
        names = List.copyOf(resolved.keySet());
        accessors = Map.copyOf(resolved);
    }

    static HostRecordSchema of(Class<?> type) {
        return CACHE.computeIfAbsent(type, HostRecordSchema::new);
    }

    List<String> names() {
        return names;
    }

    Object read(Object record, String name) {
        MethodHandle accessor = accessors.get(name);
        if (accessor == null) {
            return Missing.INSTANCE;
        }
        try {
            return accessor.invoke(record);
        } catch (Throwable failure) {
            throw new HostAccessException(
                    "javascript_host_access_failed",
                    "Could not read detached record component " + name);
        }
    }

    enum Missing {
        INSTANCE
    }
}
