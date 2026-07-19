package dev.openallay.knowledge.online;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.model.CancellationSignal;
import dev.openallay.net.HttpCancellation;
import dev.openallay.net.HttpExchangeRequest;
import dev.openallay.net.HttpResponseHeaders;
import dev.openallay.net.HttpTransport;
import dev.openallay.tool.ToolResult;
import dev.openallay.tool.builtin.SearchKnowledgeTool;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class OnlineKnowledgeSourcesTest {
    @Test
    void parsesMinecraftWikiAndMcModOnlyFromTheirFixedOrigins() {
        FakeTransport transport = new FakeTransport(Map.of(
                "minecraft.wiki", """
                        {"query":{"search":[{"title":"Poison","snippet":"<span>Poison</span> damages over time."}]}}
                        """,
                "search.mcmod.cn", """
                        <div class="search-result-list">
                          <div class="result-item"><div class="head"><a target="_blank" href="https://www.mcmod.cn/class/2820.html">[FD] <em>农夫乐事</em></a></div><div class="body">丰富烹饪与食物。</div></div>
                        </div>
                        """));
        CancellationSignal cancellation = new CancellationSignal();

        List<OnlineKnowledgeSource.RawHit> wiki = new MinecraftWikiKnowledgeSource(
                transport, new Gson()).search("poison", 3, cancellation).join();
        List<OnlineKnowledgeSource.RawHit> mcmod = new McModKnowledgeSource(transport)
                .search("农夫乐事", 3, cancellation).join();

        assertEquals("Poison", wiki.getFirst().title());
        assertEquals("Poison damages over time.", wiki.getFirst().excerpt());
        assertEquals("[FD] 农夫乐事", mcmod.getFirst().title());
        assertTrue(transport.requests.stream().anyMatch(request ->
                request.uri().getHost().equals("minecraft.wiki")));
        assertTrue(transport.requests.stream().anyMatch(request ->
                request.uri().getHost().equals("search.mcmod.cn")));
    }

    @Test
    void oneUnavailableSourceDoesNotDiscardAnotherSourceOrItsEvidence() {
        OnlineKnowledgeSource working = source(
                "openallay:working", "openallay:working_api",
                CompletableFuture.completedFuture(List.of(new OnlineKnowledgeSource.RawHit(
                        "Useful page", "Useful excerpt", "https://example.invalid/page"))));
        OnlineKnowledgeSource unavailable = source(
                "openallay:unavailable", "openallay:unavailable_api",
                CompletableFuture.failedFuture(new OnlineKnowledgeException(
                        "online_http_status", "private transport detail")));
        OnlineKnowledgeSearchService service = new OnlineKnowledgeSearchService(
                List.of(working, unavailable));
        SearchKnowledgeTool tool = new SearchKnowledgeTool(new KnowledgeRegistry(), service);

        ToolResult.Success<SearchKnowledgeTool.Output> result = assertInstanceOf(
                ToolResult.Success.class,
                tool.invokeAsync(
                        ToolInvocationContext.developmentConsole("test"),
                        new SearchKnowledgeTool.Input(
                                "useful", 5, SearchKnowledgeTool.Scope.ALL),
                        new CancellationSignal()).join());

        assertEquals(1, result.value().onlineResults().size());
        assertEquals(2, result.value().evidence().size());
        assertTrue(result.value().evidence().stream().anyMatch(evidence ->
                evidence.sourceId().equals("openallay:working")));
        assertEquals("online_http_status", result.value().diagnostics().getFirst().code());
        assertEquals("The public knowledge source returned an error",
                result.value().diagnostics().getFirst().message());
    }

    private static OnlineKnowledgeSource source(
            String id,
            String provenance,
            CompletableFuture<List<OnlineKnowledgeSource.RawHit>> result) {
        return new OnlineKnowledgeSource() {
            @Override public String sourceId() { return id; }
            @Override public String provenance() { return provenance; }
            @Override
            public CompletableFuture<List<RawHit>> search(
                    String query, int limit, HttpCancellation cancellation) {
                return result;
            }
        };
    }

    private static final class FakeTransport implements HttpTransport {
        private final Map<String, String> bodies;
        private final List<HttpExchangeRequest> requests = new ArrayList<>();

        private FakeTransport(Map<String, String> bodies) {
            this.bodies = Map.copyOf(bodies);
        }

        @Override
        public <T> CompletableFuture<T> execute(
                HttpExchangeRequest request,
                HttpCancellation cancellation,
                ResponseDecoder<T> decoder) {
            requests.add(request);
            String body = bodies.get(request.uri().getHost());
            if (body == null) {
                return CompletableFuture.failedFuture(new IOException("unexpected origin"));
            }
            try {
                return CompletableFuture.completedFuture(decoder.decode(
                        200,
                        new HttpResponseHeaders(Map.of()),
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }
}
