package dev.openallay.context;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ContextArchitectureTest {
    private static final Set<String> FORBIDDEN_PREFIXES =
            Set.of("net.minecraft.", "net.fabricmc.", "net.neoforged.");

    @Test
    void invocationContextContainsNoLiveMinecraftOrLoaderTypes() {
        Set<String> violations = new HashSet<>();
        inspect(ToolInvocationContext.class, new HashSet<>(), violations);
        assertTrue(violations.isEmpty(), () -> "Forbidden context types: " + violations);
    }

    private static void inspect(Type type, Set<Type> visited, Set<String> violations) {
        if (!visited.add(type)) {
            return;
        }
        if (type instanceof Class<?> value) {
            String name = value.getName();
            if (FORBIDDEN_PREFIXES.stream().anyMatch(name::startsWith)) {
                violations.add(name);
            }
            if (value.isRecord() && name.startsWith("dev.openallay.context.")) {
                for (var component : value.getRecordComponents()) {
                    inspect(component.getGenericType(), visited, violations);
                }
            }
        } else if (type instanceof ParameterizedType value) {
            inspect(value.getRawType(), visited, violations);
            for (Type argument : value.getActualTypeArguments()) {
                inspect(argument, visited, violations);
            }
        } else if (type instanceof GenericArrayType value) {
            inspect(value.getGenericComponentType(), visited, violations);
        } else if (type instanceof WildcardType value) {
            for (Type bound : value.getUpperBounds()) {
                inspect(bound, visited, violations);
            }
            for (Type bound : value.getLowerBounds()) {
                inspect(bound, visited, violations);
            }
        }
    }
}
