package dev.openallay.script.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.openallay.testing.GroundedTestFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

final class JavascriptDataModuleRegistryTest {
    @Test
    void capturesDetachedModulesAndIsolatesOptionalAdapterFailures() {
        JavascriptDataModuleRegistry registry = new JavascriptDataModuleRegistry();
        registry.register("test", List.of(
                new JavascriptDataModule() {
                    @Override
                    public String id() {
                        return "example:machines";
                    }

                    @Override
                    public Snapshot capture(dev.openallay.context.ToolInvocationContext context) {
                        return new Snapshot(
                                JsonParser.parseString("[{\"id\":\"example:crusher\"}]"),
                                List.of(GroundedTestFixtures.serverEvidence()));
                    }
                },
                new JavascriptDataModule() {
                    @Override
                    public String id() {
                        return "example:optional";
                    }

                    @Override
                    public Snapshot capture(dev.openallay.context.ToolInvocationContext context) {
                        throw new IllegalStateException("optional mod API absent");
                    }
                }));

        var snapshot = registry.capture(GroundedTestFixtures.fullContext());

        assertEquals("example:crusher", ((com.google.gson.JsonElement) snapshot.values()
                .get("example:machines")).getAsJsonArray()
                .get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("module_capture_failed", snapshot.diagnostics()
                .get(0).code());
        assertTrue(snapshot.evidence().contains(GroundedTestFixtures.serverEvidence()));
    }

    @Test
    void rejectsDuplicateIdsWithinOneProviderBatchBeforePublishingAnything() {
        JavascriptDataModuleRegistry registry = new JavascriptDataModuleRegistry();
        JavascriptDataModule first = module("example:duplicate");
        JavascriptDataModule second = module("example:duplicate");

        assertThrows(
                IllegalStateException.class,
                () -> registry.register("example-provider", List.of(first, second)));

        assertTrue(registry.capture(GroundedTestFixtures.fullContext())
                .values()
                .isEmpty());
    }

    @Test
    void duplicateDiagnosticsIdentifyBothProviders() {
        JavascriptDataModuleRegistry registry = new JavascriptDataModuleRegistry();
        registry.register("first-provider", List.of(module("example:owned")));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> registry.register("second-provider", List.of(module("example:owned"))));

        assertTrue(failure.getMessage().contains("first-provider"));
        assertTrue(failure.getMessage().contains("second-provider"));
    }

    private static JavascriptDataModule module(String id) {
        return new JavascriptDataModule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Snapshot capture(dev.openallay.context.ToolInvocationContext context) {
                return new Snapshot(
                        JsonParser.parseString("[]"),
                        List.of(GroundedTestFixtures.serverEvidence()));
            }
        };
    }
}
