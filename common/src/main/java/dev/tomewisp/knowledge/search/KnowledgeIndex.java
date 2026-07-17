package dev.tomewisp.knowledge.search;

import dev.tomewisp.knowledge.KnowledgeDocument;
import dev.tomewisp.knowledge.KnowledgeSnapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class KnowledgeIndex {
    private final KnowledgeSnapshot snapshot;
    private final KnowledgeTokenizer tokenizer;

    public KnowledgeIndex(KnowledgeSnapshot snapshot) {
        this(snapshot, new KnowledgeTokenizer());
    }

    public KnowledgeIndex(KnowledgeSnapshot snapshot, KnowledgeTokenizer tokenizer) {
        this.snapshot = snapshot;
        this.tokenizer = tokenizer;
    }

    public List<KnowledgeSearchResult> search(String query, Integer limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (limit != null && limit <= 0) {
            throw new IllegalArgumentException("limit must be positive when present");
        }
        String normalizedQuery = tokenizer.normalize(query);
        List<String> terms = tokenizer.tokenize(query).stream().distinct().toList();
        List<KnowledgeSearchResult> results = new ArrayList<>();
        for (KnowledgeDocument document : snapshot.documents()) {
            Score score = score(document, normalizedQuery, terms);
            if (score.value > 0) {
                results.add(new KnowledgeSearchResult(
                        document.sourceId(),
                        document.documentId(),
                        document.kind(),
                        document.title(),
                        excerpt(document.body(), normalizedQuery),
                        score.value,
                        score.fields,
                        document.provenance(),
                        document.evidence()));
            }
        }
        results.sort(java.util.Comparator.comparingInt(KnowledgeSearchResult::score)
                .reversed()
                .thenComparing(KnowledgeSearchResult::sourceId)
                .thenComparing(KnowledgeSearchResult::documentId));
        return limit == null || limit >= results.size()
                ? List.copyOf(results)
                : List.copyOf(results.subList(0, limit));
    }

    private Score score(KnowledgeDocument document, String query, List<String> terms) {
        int value = 0;
        Set<String> fields = new HashSet<>();
        String id = tokenizer.normalize(document.documentId());
        String title = tokenizer.normalize(document.title());
        String body = tokenizer.normalize(document.body());
        String namespace = tokenizer.normalize(document.namespace());
        Set<String> items = normalized(document.itemIds());
        Set<String> recipes = normalized(document.recipeIds());
        if (id.equals(query)) {
            value += 100_000;
            fields.add("documentId");
        } else if (id.contains(query)) {
            value += 20_000;
            fields.add("documentId");
        }
        if (items.contains(query) || recipes.contains(query)) {
            value += 80_000;
            fields.add(items.contains(query) ? "itemIds" : "recipeIds");
        }
        if (title.equals(query)) {
            value += 60_000;
            fields.add("title");
        } else if (title.contains(query)) {
            value += 30_000;
            fields.add("title");
        }
        if (namespace.equals(query)) {
            value += 10_000;
            fields.add("namespace");
        }
        for (String term : terms) {
            if (term.length() == 1 && terms.size() > 1) {
                continue;
            }
            value += occurrences(title, term) * 500;
            value += occurrences(id, term) * 400;
            value += occurrences(body, term) * 20;
            if (body.contains(term)) {
                fields.add("body");
            }
        }
        if (body.contains(query)) {
            value += 5_000;
            fields.add("body");
        }
        return new Score(value, fields);
    }

    private Set<String> normalized(Set<String> values) {
        Set<String> normalized = new HashSet<>();
        values.forEach(value -> normalized.add(tokenizer.normalize(value)));
        return normalized;
    }

    private static int occurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static String excerpt(String body, String query) {
        if (body.isBlank()) {
            return "";
        }
        String normalized = body.toLowerCase(java.util.Locale.ROOT);
        int match = normalized.indexOf(query);
        if (match < 0) {
            match = 0;
        }
        int start = Math.max(0, match - 80);
        int end = Math.min(body.length(), match + Math.max(query.length(), 1) + 160);
        return body.substring(start, end);
    }

    private record Score(int value, Set<String> fields) {}
}
