package dev.openallay.context.minecraft;

import com.google.gson.JsonElement;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * Trusted Extension hook for public registry facts that have no data-component or registry codec.
 * It runs only during owning-thread capture; returned JSON is detached immediately.
 */
public interface RegistryPropertyContributor {
    String id();

    Map<String, JsonElement> capture(String kind, Identifier resourceId, Object registryValue);
}
