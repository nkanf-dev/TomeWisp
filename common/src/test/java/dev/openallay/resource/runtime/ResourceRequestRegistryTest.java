package dev.openallay.resource.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonParser;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.model.CancellationSignal;
import dev.openallay.platform.InstalledModMetadata;
import dev.openallay.platform.PlatformService;
import dev.openallay.resource.mod.ModResourceSnapshot;
import dev.openallay.resource.result.ResourceResultLineage;
import dev.openallay.resource.result.ResourceResultStore;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.testing.GroundedTestFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ResourceRequestRegistryTest {
    @Test
    void capturesOneAuthorizedNamespaceAndRefreshesDynamicResults() {
        ResourceRequestRegistry registry = new ResourceRequestRegistry(new TestPlatform(), new KnowledgeRegistry());
        var context = GroundedTestFixtures.fullContext();
        UUID requestId = UUID.fromString("fd54821a-3454-4aee-b6b5-561318aa56c4");
        ResourceRequestRegistry.RequestHandle handle = registry.open(
                GroundedTestFixtures.PLAYER_ID, "main", requestId, 7, "client", Set.of("resource_read"),
                new dev.openallay.agent.context.ContextBudget(100_000, 4_096), context);

        var first = registry.capture(context, new CancellationSignal());
        assertTrue(first.view().require(ResourcePath.parse("/mod/openallay")).truth()
                instanceof ResourceValue.RecordValue);
        assertTrue(first.view().require(ResourcePath.parse("/recipe/minecraft/iron_block/@schema")).truth()
                instanceof ResourceValue.RecordValue);

        ResourceResultStore.Publication publication = new ResourceResultStore.Publication(
                "call-1",
                new ResourceResultLineage(
                        List.of(ResourcePath.parse("/item/minecraft/iron_block")),
                        List.of(),
                        ResourceResultLineage.digestOperation("resource_read", "{}")),
                ResourceKind.RECORD,
                new ResourceValue.RecordValue(Map.of("found", new ResourceValue.Scalar(true))),
                first.operationEvidence(),
                ResourcePresentation.none());
        var record = first.results().publish(first.resultScope(), publication,
                path -> first.view().require(path) != null);

        var refreshed = registry.capture(context, new CancellationSignal());
        assertSame(first.view(), refreshed.view());
        assertEquals(record.node().truth(), refreshed.view().require(record.path()).truth());

        handle.close();
        assertThrows(IllegalStateException.class, () -> registry.capture(context, new CancellationSignal()));
        registry.close();
    }

    @Test
    void importsRemoteExactTruthIntoOwnerLocalResultAndBuildsStructuredReceipt() {
        ResourceRequestRegistry registry = new ResourceRequestRegistry(
                new TestPlatform(), new KnowledgeRegistry());
        var context = GroundedTestFixtures.fullContext();
        UUID requestId = UUID.fromString("af5c2f16-4106-4d39-9c0e-d531be80f11a");
        try (var handle = registry.open(
                GroundedTestFixtures.PLAYER_ID,
                "main",
                requestId,
                9,
                "server",
                Set.of("openallay:resource_read"),
                new dev.openallay.agent.context.ContextBudget(100_000, 4_096),
                context)) {
            var normalized = JsonParser.parseString("""
                    {
                      "status":"success",
                      "outputType":"dev.openallay.tool.resource.ResourceToolOutput",
                      "value":{
                        "operation":"resource_read",
                        "resultPath":"/result/remote-only",
                        "items":[{
                          "inputIndex":0,
                          "input":"/game/options",
                          "status":"success",
                          "value":{
                            "path":"/game/options",
                            "kind":"record",
                            "presentation":"options",
                            "presentationReferences":{},
                            "value":{"gui_scale":3}
                          }
                        }],
                        "evidence":[{
                          "authority":"CLIENT_VISIBLE",
                          "completeness":"COMPLETE",
                          "capturedAt":"1970-01-01T00:00:00Z",
                          "sourceId":"minecraft:client_options",
                          "provenance":"minecraft:options_screen",
                          "gameVersion":"26.2",
                          "loader":"fabric",
                          "details":{}
                        }]
                      }
                    }
                    """).getAsJsonObject();

            var imported = registry.importRemoteResult(
                    context.correlationId(),
                    "openallay:resource_read",
                    "remote-call-1",
                    normalized,
                    "client");

            assertFalse(imported.failure());
            assertTrue(imported.uiReference().resultPath().toString().startsWith("/result/"));
            assertFalse(imported.uiReference().resultPath().toString().contains("remote-only"));
            assertEquals(imported.uiReference().resultPath(),
                    imported.modelView().receipts().getFirst().resultPath());
            assertEquals("client_visible", imported.modelView().receipts().getFirst().authority());
            assertEquals(1, imported.modelView().receipts().getFirst().returned());

            var ownerSession = registry.capture(context, new CancellationSignal());
            assertEquals(
                    "resource_read",
                    ((ResourceValue.Scalar) ((ResourceValue.RecordValue) ownerSession.view()
                                    .require(imported.uiReference().resultPath()).truth())
                            .fields().get("operation")).value());
            assertEquals(
                    "client_to_model_owner",
                    ownerSession.view().require(imported.uiReference().resultPath())
                            .evidence().details().get("openallay:bridge_route"));
        }
        registry.close();
    }

    private static final class TestPlatform implements PlatformService {
        @Override public String platformName() { return "test"; }
        @Override public boolean isModLoaded(String modId) { return modId.equals("openallay"); }
        @Override public boolean isDevelopmentEnvironment() { return true; }
        @Override public String gameVersion() { return "26.2"; }
        @Override public List<InstalledModMetadata> installedMods() { return List.of(); }
        @Override public ModResourceSnapshot captureModResources() {
            return ModResourceSnapshot.unavailable(Instant.EPOCH, "fixture");
        }
    }
}
