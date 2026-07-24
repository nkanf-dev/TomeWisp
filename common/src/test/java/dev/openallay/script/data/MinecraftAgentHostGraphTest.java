package dev.openallay.script.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.knowledge.KnowledgeSnapshot;
import dev.openallay.script.extension.JavascriptDataModule;
import dev.openallay.script.extension.JavascriptDataModuleRegistry;
import dev.openallay.testing.JavascriptAgentTestFixtures;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class MinecraftAgentHostGraphTest {
    @Test
    void selectsOriginalRegistryReferencesWithoutResolvingUnselectedRoots() {
        var context = JavascriptAgentTestFixtures.context("direct-host-graph");
        AtomicInteger knowledgeCaptures = new AtomicInteger();
        AtomicInteger extensionCaptures = new AtomicInteger();
        JavascriptDataModuleRegistry extensions = new JavascriptDataModuleRegistry();
        extensions.register("test", List.of(new JavascriptDataModule() {
            @Override public String id() { return "test:direct"; }

            @Override
            public Snapshot capture(dev.openallay.context.ToolInvocationContext ignored) {
                extensionCaptures.incrementAndGet();
                return new Snapshot(
                        new ModuleRecord("retained"),
                        List.of(context.registries().orElseThrow().evidence()));
            }
        }));
        MinecraftAgentHostGraph graph = new MinecraftAgentHostGraph(
                context,
                () -> {
                    knowledgeCaptures.incrementAndGet();
                    return KnowledgeSnapshot.empty();
                },
                extensions);

        Map<String, Object> selected = graph.select(Set.of("items"));
        assertEquals(0, knowledgeCaptures.get());
        assertEquals(0, extensionCaptures.get());
        @SuppressWarnings("unchecked")
        List<dev.openallay.context.RegistryEntrySnapshot> items =
                (List<dev.openallay.context.RegistryEntrySnapshot>) selected.get("items");
        var expected = context.registries().orElseThrow().entries().stream()
                .filter(entry -> entry.kind().equals("item"))
                .findFirst().orElseThrow();
        assertSame(expected, items.getFirst());
        assertEquals(0, knowledgeCaptures.get());
        assertEquals(0, extensionCaptures.get());

        Map<String, Object> all = graph.select(Set.of());
        int entries = 0;
        for (Map.Entry<String, Object> entry : all.entrySet()) {
            assertTrue(!entry.getKey().isBlank());
            entries++;
        }
        assertTrue(entries > 0);
        assertEquals(0, knowledgeCaptures.get());
        assertEquals(0, extensionCaptures.get());
        all.get("knowledge");
        all.get("knowledge");
        all.get("extensions");
        all.get("extensions");
        assertEquals(1, knowledgeCaptures.get());
        assertEquals(1, extensionCaptures.get());
        assertTrue(graph.evidence().contains(context.registries().orElseThrow().evidence()));
    }

    private record ModuleRecord(String value) {}
}
