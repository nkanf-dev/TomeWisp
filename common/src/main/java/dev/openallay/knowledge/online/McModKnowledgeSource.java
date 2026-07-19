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
import java.util.concurrent.ConcurrentHashMap;
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
    private final ConcurrentHashMap<String, URI> issued = new ConcurrentHashMap<>();
    private static final Pattern ARTICLE = Pattern.compile(
            "(?is)<(?:article|main)[^>]*>(.*?)</(?:article|main)>"
                    + "|<div[^>]*class=\\\"[^\\\"]*(?:text-area|common-text|class-info)[^\\\"]*\\\"[^>]*>(.*?)</div>");

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
                URI target = URI.create("https://www.mcmod.cn").resolve(matcher.group(1));
                String reference = "mcmod_" + Integer.toUnsignedString(target.toString().hashCode(), 36);
                issued.put(reference, target);
                hits.add(new RawHit(
                        MinecraftWikiKnowledgeSource.cleanHtml(matcher.group(2)),
                        MinecraftWikiKnowledgeSource.cleanHtml(matcher.group(3)),
                        reference));
            }
            if (hits.isEmpty() && !html.contains("search-result-list")) {
                throw new OnlineKnowledgeException(
                        "online_parse_failed", "MC百科 response has no readable result list");
            }
            return List.copyOf(hits);
        });
    }

    @Override
    public CompletableFuture<RawDocument> fetch(
            String reference, HttpCancellation cancellation) {
        URI uri = issued.get(reference);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || !"www.mcmod.cn".equalsIgnoreCase(uri.getHost())) {
            return CompletableFuture.failedFuture(new OnlineKnowledgeException(
                    "online_document_unavailable", "MC百科 document reference is unavailable"));
        }
        HttpExchangeRequest request = HttpExchangeRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "text/html;charset=UTF-8")
                .header("User-Agent", "OpenAllay/1.0 (Minecraft knowledge integration)")
                .get()
                .build();
        return transport.execute(request, cancellation, (status, headers, body) -> {
            if (status < 200 || status >= 300) {
                throw new OnlineKnowledgeException(
                        "online_http_status", "MC百科 article request failed");
            }
            String html = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            String title = first(html, Pattern.compile("(?is)<title[^>]*>(.*?)</title>"));
            String safe = html.replaceAll("(?is)<(script|style|nav|form)[^>]*>.*?</\\1>", " ");
            Matcher article = ARTICLE.matcher(safe);
            String selected = article.find()
                    ? (article.group(1) == null ? article.group(2) : article.group(1))
                    : first(safe, Pattern.compile("(?is)<body[^>]*>(.*?)</body>"));
            String text = MinecraftWikiKnowledgeSource.cleanHtml(selected)
                    .replaceAll("(?i)javascript:", "")
                    .strip();
            if (text.isBlank()) {
                throw new OnlineKnowledgeException(
                        "online_parse_failed", "MC百科 article has no readable body");
            }
            return new RawDocument(
                    title.isBlank() ? "MC百科" : MinecraftWikiKnowledgeSource.cleanHtml(title),
                    text,
                    reference);
        });
    }

    private static String first(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }
}
