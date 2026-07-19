package dev.openallay.knowledge.online;

import dev.openallay.net.HttpCancellation;
import dev.openallay.net.HttpExchangeRequest;
import dev.openallay.net.HttpTransport;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fixed-origin parser for the public MC百科 search result page. */
public final class McModKnowledgeSource implements OnlineKnowledgeSource {
    private static final String SOURCE_ID = "openallay:mcmod";
    private static final String PROVENANCE = "openallay:mcmod_search";
    private static final Pattern RESULT = Pattern.compile(
            "<div class=\\\"result-item\\\">.*?<div class=\\\"head\\\">.*?"
                    + "<a\\s+target=\\\"_blank\\\"\\s+href=\\\"(https://www\\.mcmod\\.cn/[^\\\"]+)\\\">"
                    + "(.*?)</a></div><div class=\\\"body\\\">(.*?)</div>",
            Pattern.DOTALL);
    private final HttpTransport transport;

    public McModKnowledgeSource(HttpTransport transport) {
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
    }

    @Override public String sourceId() { return SOURCE_ID; }
    @Override public String provenance() { return PROVENANCE; }

    @Override
    public CompletableFuture<List<RawHit>> search(
            String query, int limit, HttpCancellation cancellation) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://search.mcmod.cn/s?mold=1&key=" + encoded);
        HttpExchangeRequest request = HttpExchangeRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "text/html;charset=UTF-8")
                .header("User-Agent", "OpenAllay/1.0 (Minecraft knowledge integration)")
                .get()
                .build();
        return transport.execute(request, cancellation, (status, headers, body) -> {
            if (status < 200 || status >= 300) {
                throw new OnlineKnowledgeException(
                        "online_http_status", "MC百科 search failed");
            }
            String html = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = RESULT.matcher(html);
            List<RawHit> hits = new ArrayList<>();
            while (matcher.find() && hits.size() < limit) {
                hits.add(new RawHit(
                        MinecraftWikiKnowledgeSource.cleanHtml(matcher.group(2)),
                        MinecraftWikiKnowledgeSource.cleanHtml(matcher.group(3)),
                        matcher.group(1)));
            }
            if (hits.isEmpty() && !html.contains("search-result-list")) {
                throw new OnlineKnowledgeException(
                        "online_parse_failed", "MC百科 response has no readable result list");
            }
            return List.copyOf(hits);
        });
    }
}
