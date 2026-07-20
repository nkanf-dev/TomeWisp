package dev.openallay.knowledge.online;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.openallay.net.HttpCancellation;
import dev.openallay.net.HttpExchangeRequest;
import dev.openallay.net.HttpTransport;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Fixed-origin Minecraft Wiki MediaWiki Action API adapter. */
public final class MinecraftWikiKnowledgeSource implements OnlineKnowledgeSource {
    private static final String SOURCE_ID = "openallay:minecraft_wiki";
    private static final String PROVENANCE = "openallay:minecraft_wiki_api";
    private final HttpTransport transport;
    private final Gson gson;

    public MinecraftWikiKnowledgeSource(HttpTransport transport, Gson gson) {
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.gson = java.util.Objects.requireNonNull(gson, "gson");
    }

    @Override public String sourceId() { return SOURCE_ID; }
    @Override public String provenance() { return PROVENANCE; }

    @Override
    public CompletableFuture<List<RawHit>> search(
            String query, int limit, HttpCancellation cancellation) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String apiLimit = limit == Integer.MAX_VALUE ? "max" : Integer.toString(limit);
        URI uri = URI.create("https://minecraft.wiki/api.php?action=query&list=search"
                + "&format=json&utf8=1&srprop=snippet&srlimit=" + apiLimit + "&srsearch=" + encoded);
        HttpExchangeRequest request = HttpExchangeRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("User-Agent", "OpenAllay/1.0 (Minecraft knowledge integration)")
                .get()
                .build();
        return transport.execute(request, cancellation, (status, headers, body) -> {
            if (status < 200 || status >= 300) {
                throw new OnlineKnowledgeException(
                        "online_http_status", "Minecraft Wiki search failed");
            }
            JsonObject root = gson.fromJson(
                    new InputStreamReader(body, StandardCharsets.UTF_8), JsonObject.class);
            JsonArray results = root == null || !root.has("query")
                    ? null
                    : root.getAsJsonObject("query").getAsJsonArray("search");
            if (results == null) {
                throw new OnlineKnowledgeException(
                        "online_parse_failed", "Minecraft Wiki response has no search results");
            }
            List<RawHit> hits = new ArrayList<>();
            for (JsonElement element : results) {
                JsonObject value = element.getAsJsonObject();
                String title = value.get("title").getAsString();
                String excerpt = cleanHtml(value.has("snippet")
                        ? value.get("snippet").getAsString()
                        : "");
                String reference = "https://minecraft.wiki/w/"
                        + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8)
                                .replace("+", "%20");
                hits.add(new RawHit(title, excerpt, reference));
            }
            return List.copyOf(hits);
        });
    }

    @Override
    public CompletableFuture<RawDocument> read(
            String reference, HttpCancellation cancellation) {
        URI discovered = fixedDocumentReference(reference);
        String encodedTitle = discovered.getRawPath().substring("/w/".length());
        String title = URLDecoder.decode(encodedTitle, StandardCharsets.UTF_8).replace('_', ' ');
        String encoded = URLEncoder.encode(title, StandardCharsets.UTF_8);
        URI uri = URI.create("https://minecraft.wiki/api.php?action=parse&format=json"
                + "&formatversion=2&prop=text&page=" + encoded);
        HttpExchangeRequest request = HttpExchangeRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("User-Agent", "OpenAllay/1.0 (Minecraft knowledge integration)")
                .get()
                .build();
        return transport.execute(request, cancellation, (status, headers, body) -> {
            if (status < 200 || status >= 300) {
                throw new OnlineKnowledgeException(
                        "online_http_status", "Minecraft Wiki document read failed");
            }
            JsonObject root = gson.fromJson(
                    new InputStreamReader(body, StandardCharsets.UTF_8), JsonObject.class);
            JsonObject parsed = root == null || !root.has("parse")
                    ? null : root.getAsJsonObject("parse");
            if (parsed == null || !parsed.has("text")) {
                throw new OnlineKnowledgeException(
                        "online_parse_failed", "Minecraft Wiki response has no document text");
            }
            String resolvedTitle = parsed.has("title") ? parsed.get("title").getAsString() : title;
            List<RawSection> sections = OnlineKnowledgeHtml.sections(
                    resolvedTitle, parsed.get("text").getAsString());
            return new RawDocument(resolvedTitle, sections, discovered.toString());
        });
    }

    private static URI fixedDocumentReference(String reference) {
        URI uri;
        try {
            uri = URI.create(reference);
        } catch (RuntimeException failure) {
            throw new OnlineKnowledgeException(
                    "online_reference_invalid", "Minecraft Wiki reference is invalid");
        }
        if (!"https".equals(uri.getScheme()) || !"minecraft.wiki".equals(uri.getHost())
                || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getRawPath() == null
                || !uri.getRawPath().startsWith("/w/") || uri.getRawPath().length() <= 3) {
            throw new OnlineKnowledgeException(
                    "online_reference_invalid", "Minecraft Wiki reference is outside the fixed origin");
        }
        return uri;
    }

    static String cleanHtml(String value) {
        return value.replaceAll("(?s)<[^>]*>", "")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
