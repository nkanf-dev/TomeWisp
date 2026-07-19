package dev.tomewisp.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.settings.SettingsWriteException;
import dev.tomewisp.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CapabilityPolicyStoreTest {
    @TempDir Path temporary;

    @Test
    void missingFileLoadsDefaultsWithoutCreatingIt() {
        Path target = temporary.resolve("capabilities.json");
        CapabilityPolicyStore store = new CapabilityPolicyStore(target);

        assertEquals(CapabilityPolicy.defaults(), success(store.load()).value());
        assertEquals(CapabilityPolicy.defaults(), store.current());
        assertFalse(Files.exists(target));
    }

    @Test
    void atomicallySavesCanonicalPolicyBeforePublishingIt() throws Exception {
        Path target = temporary.resolve("capabilities.json");
        CapabilityPolicyStore store = new CapabilityPolicyStore(target);
        CapabilityPolicy candidate = policy(Set.of("zeta:tool", "alpha:tool"), Set.of("zeta", "alpha"));

        CapabilityPolicy saved = success(store.save(candidate)).value();

        assertEquals(candidate, saved);
        assertEquals(candidate, store.current());
        assertTrue(Files.exists(target));
        String encoded = Files.readString(target);
        assertTrue(encoded.indexOf("alpha:tool") < encoded.indexOf("zeta:tool"));
        assertTrue(encoded.indexOf("alpha") < encoded.indexOf("zeta"));
    }

    @Test
    void failedAtomicMoveRetainsPriorFileAndPublishedPolicy() throws Exception {
        Path target = temporary.resolve("capabilities.json");
        CapabilityPolicy prior = policy(Set.of("prior:tool"), Set.of("prior-skill"));
        Files.writeString(target, new CapabilityPolicyWriter().encode(prior));
        CapabilityPolicyStore store = new CapabilityPolicyStore(
                target,
                (ignoredPath, ignoredContents) -> {
                    throw new SettingsWriteException();
                });
        assertEquals(prior, success(store.load()).value());
        String priorBytes = Files.readString(target);

        ToolResult.Failure<CapabilityPolicy> failure = failure(store.save(
                policy(Set.of("new:tool"), Set.of("new-skill"))));

        assertEquals("settings_write_failed", failure.code());
        assertEquals(priorBytes, Files.readString(target));
        assertEquals(prior, store.current());
    }

    @Test
    void invalidFileDoesNotReplaceCurrentPolicy() throws Exception {
        Path target = temporary.resolve("capabilities.json");
        CapabilityPolicyStore store = new CapabilityPolicyStore(target);
        CapabilityPolicy prior = policy(Set.of("prior:tool"), Set.of());
        success(store.save(prior));
        Files.writeString(target, "{}\n");

        assertEquals("invalid_capability_config", failure(store.load()).code());
        assertEquals(prior, store.current());
    }

    private static CapabilityPolicy policy(Set<String> tools, Set<String> skills) {
        return new CapabilityPolicy(CapabilityPolicy.SCHEMA_VERSION, tools, skills);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Success<CapabilityPolicy> success(ToolResult<CapabilityPolicy> result) {
        return (ToolResult.Success<CapabilityPolicy>)
                assertInstanceOf(ToolResult.Success.class, result);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Failure<CapabilityPolicy> failure(ToolResult<CapabilityPolicy> result) {
        return (ToolResult.Failure<CapabilityPolicy>)
                assertInstanceOf(ToolResult.Failure.class, result);
    }
}
