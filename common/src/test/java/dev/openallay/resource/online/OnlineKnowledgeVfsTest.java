package dev.openallay.resource.online;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.openallay.agent.context.ContextBudget;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.knowledge.online.McModKnowledgeSource;
import dev.openallay.knowledge.online.MinecraftWikiKnowledgeSource;
import dev.openallay.knowledge.online.OnlineKnowledgeException;
import dev.openallay.knowledge.online.OnlineKnowledgeSearchService;
import dev.openallay.model.CancellationSignal;
import dev.openallay.net.HttpCancellation;
import dev.openallay.net.HttpExchangeRequest;
import dev.openallay.net.HttpResponseHeaders;
import dev.openallay.net.HttpTransport;
import dev.openallay.platform.InstalledModMetadata;
import dev.openallay.platform.PlatformService;
import dev.openallay.resource.mod.ModResourceSnapshot;
import dev.openallay.resource.runtime.ResourceRequestRegistry;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourceSearchIndex;
import dev.openallay.testing.GroundedTestFixtures;
import dev.openallay.tool.ToolResult;
import dev.openallay.tool.resource.ResourceGrepTool;
import dev.openallay.tool.resource.ResourceReadTool;
import dev.openallay.tool.resource.ResourceToolOutput;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class OnlineKnowledgeVfsTest {
    @Test
    void discoversThenReadsFixedOriginDocumentThroughExistingVfsTools() {
        RoutingTransport transport = new RoutingTransport();
        OnlineKnowledgeSearchService online = new OnlineKnowledgeSearchService(List.of(
                new MinecraftWikiKnowledgeSource(transport, new Gson()),
                new McModKnowledgeSource(transport)));
        ResourceRequestRegistry registry = new ResourceRequestRegistry(
                new TestPlatform(), new KnowledgeRegistry(), online);
        var context = GroundedTestFixtures.fullContext();
        var handle = registry.open(
                GroundedTestFixtures.PLAYER_ID,
                "main",
                UUID.fromString("c32e0505-96ec-426a-8136-8a087c2b38e7"),
                1,
                "client",
                Set.of("resource_grep", "resource_read"),
                new ContextBudget(100_000, 4_096),
                context);
        try {
            ResourceGrepTool grep = new ResourceGrepTool(registry);
            var searched = assertInstanceOf(ToolResult.Success.class, grep.invokeAsync(
                    context,
                    new ResourceGrepTool.Input(List.of(new ResourceGrepTool.Search(
                            List.of("/knowledge/online"),
                            "poison",
                            ResourceSearchIndex.Mode.TOKEN,
                            List.of())), null),
                    new CancellationSignal()).join());
            ResourceToolOutput searchOutput = (ResourceToolOutput) searched.value();
            assertTrue(searchOutput.items().stream().anyMatch(item -> item.value() != null
                    && item.value().getAsJsonObject().has("path")));
            assertTrue(searchOutput.items().stream().anyMatch(item -> item.failure() != null
                    && item.failure().code().equals("online_http_status")));
            String documentPath = searchOutput.items().stream()
                    .filter(item -> item.value() != null && item.value().getAsJsonObject().has("path"))
                    .findFirst().orElseThrow().value().getAsJsonObject().get("path").getAsString();
            assertTrue(documentPath.matches("/knowledge/online/minecraft_wiki/[0-9a-f]{64}"));
            assertTrue(registry.resultStore().records(searchOutputScope(registry, context)).isEmpty() == false);

            ResourceReadTool read = new ResourceReadTool(registry);
            var readResult = assertInstanceOf(ToolResult.Success.class, read.invokeAsync(
                    context,
                    new ResourceReadTool.Input(List.of(documentPath), List.of(),
                            ResourceReadTool.Format.AUTO, null),
                    new CancellationSignal()).join());
            ResourceToolOutput document = (ResourceToolOutput) readResult.value();
            assertEquals(2, document.items().size());
            assertEquals("Poison", document.items().getFirst().value().getAsJsonObject()
                    .get("title").getAsString());
            assertTrue(document.modelView().text().contains("Damages living entities over time"));
            assertEquals("document", document.items().getFirst().value().getAsJsonObject()
                    .get("presentation").getAsString());

            int requestsBeforeForgery = transport.requests.size();
            String forged = "/knowledge/online/minecraft_wiki/" + "0".repeat(64);
            var rejected = assertInstanceOf(ToolResult.Success.class, read.invokeAsync(
                    context,
                    new ResourceReadTool.Input(List.of(forged), List.of(), null, null),
                    new CancellationSignal()).join());
            ResourceToolOutput rejection = (ResourceToolOutput) rejected.value();
            assertEquals("online_document_not_discovered", rejection.items().getFirst().failure().code());
            assertEquals(requestsBeforeForgery, transport.requests.size());
        } finally {
            handle.close();
            registry.close();
        }
    }

    @Test
    void sourceAdaptersRejectReferencesOutsideTheirFixedOriginsBeforeTransport() {
        RoutingTransport transport = new RoutingTransport();
        MinecraftWikiKnowledgeSource wiki = new MinecraftWikiKnowledgeSource(transport, new Gson());
        McModKnowledgeSource mcmod = new McModKnowledgeSource(transport);

        assertThrows(OnlineKnowledgeException.class,
                () -> wiki.read("https://example.com/w/Poison", new CancellationSignal()));
        assertThrows(OnlineKnowledgeException.class,
                () -> mcmod.read("https://www.mcmod.cn/../../private", new CancellationSignal()));
        assertTrue(transport.requests.isEmpty());
    }

    private static dev.openallay.resource.result.ResourceResultStore.Scope searchOutputScope(
            ResourceRequestRegistry registry, dev.openallay.context.ToolInvocationContext context) {
        return registry.capture(context, new CancellationSignal()).resultScope();
    }

    private static final class RoutingTransport implements HttpTransport {
        private final List<HttpExchangeRequest> requests = new ArrayList<>();

        @Override
        public <T> CompletableFuture<T> execute(
                HttpExchangeRequest request,
                HttpCancellation cancellation,
                ResponseDecoder<T> decoder) {
            requests.add(request);
            String body;
            int status = 200;
            if (request.uri().getHost().equals("minecraft.wiki")
                    && request.uri().getRawQuery().contains("list=search")) {
                body = """
                        {"query":{"search":[{"title":"Poison","snippet":"Poison damages over time."}]}}
                        """;
            } else if (request.uri().getHost().equals("minecraft.wiki")
                    && request.uri().getRawQuery().contains("action=parse")) {
                body = """
                        {"parse":{"title":"Poison","text":"<p>Damages living entities over time.</p><p>Milk removes the effect.</p>"}}
                        """;
            } else if (request.uri().getHost().equals("search.mcmod.cn")) {
                status = 503;
                body = "temporarily unavailable";
            } else {
                return CompletableFuture.failedFuture(new IOException("unexpected fixed-origin request"));
            }
            try {
                return CompletableFuture.completedFuture(decoder.decode(
                        status,
                        new HttpResponseHeaders(Map.of()),
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }

    private static final class TestPlatform implements PlatformService {
        @Override public String platformName() { return "test"; }
        @Override public boolean isModLoaded(String modId) { return false; }
        @Override public boolean isDevelopmentEnvironment() { return true; }
        @Override public String gameVersion() { return "26.2"; }
        @Override public List<InstalledModMetadata> installedMods() { return List.of(); }
        @Override public ModResourceSnapshot captureModResources() {
            return ModResourceSnapshot.unavailable(Instant.EPOCH, "fixture");
        }
    }
}
