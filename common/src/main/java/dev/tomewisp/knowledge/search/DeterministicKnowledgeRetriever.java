package dev.tomewisp.knowledge.search;

import dev.tomewisp.knowledge.KnowledgeDocument;
import dev.tomewisp.knowledge.KnowledgeSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Field-weighted, heading-aware BM25 retrieval with stable deterministic ordering. */
public final class DeterministicKnowledgeRetriever implements KnowledgeRetriever {
    private static final Pattern ATX_HEADING =
            Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.+?)(?:\\s+#+)?\\s*$");
    private static final double K1 = 1.2d;
    private static final double B = 0.75d;
    private static final int EXACT_DOCUMENT = 5;
    private static final int EXACT_RESOURCE = 4;
    private static final int EXACT_ALIAS = 3;
    private static final int EXACT_TITLE = 2;
    private static final int PHRASE_MATCH = 1;

    private final KnowledgeTokenizer tokenizer;
    private final List<IndexedSection> sections;
    private final Corpus corpus;

    public DeterministicKnowledgeRetriever(KnowledgeSnapshot snapshot, KnowledgeTokenizer tokenizer) {
        Objects.requireNonNull(snapshot, "snapshot");
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.sections = snapshot.documents().stream()
                .flatMap(document -> sections(document).stream())
                .toList();
        this.corpus = Corpus.from(sections);
    }

    @Override
    public List<KnowledgeSearchResult> retrieve(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalizedQuery = tokenizer.normalize(query);
        List<String> terms = significantTerms(tokenizer.tokenize(query));
        Map<String, RankedSection> bestByDocument = new HashMap<>();
        for (IndexedSection section : sections) {
            RankedSection ranked = score(section, normalizedQuery, terms, corpus);
            if (ranked == null) {
                continue;
            }
            bestByDocument.merge(section.document().key(), ranked, DeterministicKnowledgeRetriever::better);
        }

        List<RankedSection> ranked = new ArrayList<>(bestByDocument.values());
        ranked.sort(Comparator.comparingInt(RankedSection::priority)
                .reversed()
                .thenComparing(Comparator.comparingDouble(RankedSection::lexicalScore).reversed())
                .thenComparing(hit -> hit.section().document().sourceId())
                .thenComparing(hit -> hit.section().document().documentId())
                .thenComparing(hit -> hit.section().sectionId()));
        return ranked.stream().map(hit -> result(hit, normalizedQuery, terms)).toList();
    }

    private RankedSection score(
            IndexedSection section,
            String normalizedQuery,
            List<String> terms,
            Corpus corpus) {
        KnowledgeDocument document = section.document();
        Set<String> matched = new TreeSet<>();
        int priority = 0;
        double phraseScore = 0.0d;
        String documentId = tokenizer.normalize(document.documentId());
        String title = tokenizer.normalize(document.title());
        String heading = tokenizer.normalize(section.heading());
        Set<String> resources = normalized(document.itemIds(), document.recipeIds());
        Set<String> aliases = aliases(document);

        if (documentId.equals(normalizedQuery)
                || tokenizer.normalize(document.key()).equals(normalizedQuery)) {
            priority = EXACT_DOCUMENT;
            matched.add("documentId");
        }
        if (resources.contains(normalizedQuery)) {
            priority = Math.max(priority, EXACT_RESOURCE);
            matched.add(document.itemIds().stream()
                            .map(tokenizer::normalize)
                            .anyMatch(normalizedQuery::equals)
                    ? "itemIds"
                    : "recipeIds");
        }
        if (aliases.contains(normalizedQuery)) {
            priority = Math.max(priority, EXACT_ALIAS);
            matched.add("aliases");
        }
        if (title.equals(normalizedQuery) || heading.equals(normalizedQuery)) {
            priority = Math.max(priority, EXACT_TITLE);
            matched.add(title.equals(normalizedQuery) ? "title" : "sectionHeading");
        }

        phraseScore += phrase(section.titleText(), normalizedQuery, 90.0d, "title", matched);
        phraseScore += phrase(section.heading(), normalizedQuery, 75.0d, "sectionHeading", matched);
        phraseScore += phrase(section.identityText(), normalizedQuery, 60.0d, "aliases", matched);
        phraseScore += phrase(section.metadataText(), normalizedQuery, 35.0d, "metadata", matched);
        phraseScore += phrase(section.body(), normalizedQuery, 18.0d, "body", matched);
        if (phraseScore > 0.0d) {
            priority = Math.max(priority, PHRASE_MATCH);
        }

        double lexical = phraseScore;
        for (String term : terms) {
            double idf = corpus.idf(term);
            lexical += fieldScore(section.titleTokens(), term, idf, corpus.averageTitleLength(), 6.0d, "title", matched);
            lexical += fieldScore(section.headingTokens(), term, idf, corpus.averageHeadingLength(), 5.0d,
                    "sectionHeading", matched);
            lexical += fieldScore(section.identityTokens(), term, idf, corpus.averageIdentityLength(), 4.0d,
                    "aliases", matched);
            lexical += fieldScore(section.metadataTokens(), term, idf, corpus.averageMetadataLength(), 3.0d,
                    "metadata", matched);
            lexical += fieldScore(section.bodyTokens(), term, idf, corpus.averageBodyLength(), 1.0d, "body", matched);
        }
        return priority == 0 && lexical == 0.0d
                ? null
                : new RankedSection(section, priority, lexical, Set.copyOf(matched));
    }

    private double fieldScore(
            List<String> fieldTokens,
            String term,
            double idf,
            double averageLength,
            double weight,
            String field,
            Set<String> matched) {
        int frequency = 0;
        for (String token : fieldTokens) {
            if (token.equals(term)) {
                frequency++;
            }
        }
        if (frequency == 0) {
            return 0.0d;
        }
        matched.add(field);
        double lengthNormalization = 1.0d - B + B * fieldTokens.size() / Math.max(averageLength, 1.0d);
        return weight * idf * frequency * (K1 + 1.0d) / (frequency + K1 * lengthNormalization);
    }

    private double phrase(
            String value,
            String query,
            double weight,
            String field,
            Set<String> matched) {
        if (!query.isEmpty() && tokenizer.normalize(value).contains(query)) {
            matched.add(field);
            return weight;
        }
        return 0.0d;
    }

    private KnowledgeSearchResult result(
            RankedSection hit, String normalizedQuery, List<String> terms) {
        IndexedSection section = hit.section();
        KnowledgeDocument document = section.document();
        int score = Math.max(1, (int) Math.min(
                Integer.MAX_VALUE,
                hit.priority() * 100_000_000L + Math.round(hit.lexicalScore() * 1_000.0d)));
        return new KnowledgeSearchResult(
                document.sourceId(),
                document.documentId(),
                section.sectionId(),
                section.heading(),
                document.kind(),
                document.title(),
                excerpt(section.body(), normalizedQuery, terms),
                score,
                hit.matchedFields(),
                document.provenance(),
                document.evidence());
    }

    private Set<String> aliases(KnowledgeDocument document) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        addAliases(aliases, document.documentId());
        addAliases(aliases, document.key());
        document.itemIds().forEach(value -> addAliases(aliases, value));
        document.recipeIds().forEach(value -> addAliases(aliases, value));
        return Set.copyOf(aliases);
    }

    private void addAliases(Set<String> aliases, String value) {
        String normalized = tokenizer.normalize(value);
        aliases.add(normalized);
        int namespace = normalized.indexOf(':');
        String path = namespace >= 0 ? normalized.substring(namespace + 1) : normalized;
        aliases.add(path);
        aliases.add(path.replace('_', ' ').replace('-', ' ').replace('/', ' '));
    }

    private Set<String> normalized(Set<String> first, Set<String> second) {
        Set<String> normalized = new LinkedHashSet<>();
        first.forEach(value -> normalized.add(tokenizer.normalize(value)));
        second.forEach(value -> normalized.add(tokenizer.normalize(value)));
        return normalized;
    }

    private List<IndexedSection> sections(KnowledgeDocument document) {
        List<IndexedSection> result = new ArrayList<>();
        Map<String, Integer> duplicateIds = new HashMap<>();
        String heading = document.title();
        String sectionId = "document";
        StringBuilder body = new StringBuilder();
        char fence = 0;
        int fenceLength = 0;
        for (String line : document.body().split("\\R", -1)) {
            String stripped = line.stripLeading();
            int indentation = line.length() - stripped.length();
            int markerLength = fenceMarkerLength(stripped);
            if (indentation <= 3 && markerLength >= 3) {
                char marker = stripped.charAt(0);
                if (fence == 0) {
                    fence = marker;
                    fenceLength = markerLength;
                } else if (fence == marker && markerLength >= fenceLength) {
                    fence = 0;
                    fenceLength = 0;
                }
            }
            Matcher matcher = ATX_HEADING.matcher(line);
            if (fence == 0 && markerLength == 0 && matcher.matches()) {
                addSection(result, document, sectionId, heading, body.toString());
                heading = matcher.group(2).strip();
                String baseId = slug(heading);
                int occurrence = duplicateIds.merge(baseId, 1, Integer::sum);
                sectionId = occurrence == 1 ? baseId : baseId + "-" + occurrence;
                body.setLength(0);
            } else {
                if (!body.isEmpty()) {
                    body.append('\n');
                }
                body.append(line);
            }
        }
        addSection(result, document, sectionId, heading, body.toString());
        return result;
    }

    private static int fenceMarkerLength(String line) {
        if (line.isEmpty() || (line.charAt(0) != '`' && line.charAt(0) != '~')) {
            return 0;
        }
        char marker = line.charAt(0);
        int length = 0;
        while (length < line.length() && line.charAt(length) == marker) {
            length++;
        }
        return length;
    }

    private void addSection(
            List<IndexedSection> result,
            KnowledgeDocument document,
            String sectionId,
            String heading,
            String body) {
        if (!result.isEmpty() || !body.isBlank() || sectionId.equals("document")) {
            result.add(indexed(document, sectionId, heading, body.strip()));
        }
    }

    private IndexedSection indexed(
            KnowledgeDocument document, String sectionId, String heading, String body) {
        String identity = aliases(document).stream().sorted().collect(java.util.stream.Collectors.joining(" "));
        String metadata = document.sourceId() + " " + document.namespace() + " "
                + document.kind().name().toLowerCase(Locale.ROOT) + " "
                + document.evidence().sourceId() + " "
                + String.join(" ", document.evidence().details().values());
        return new IndexedSection(
                document,
                sectionId,
                heading,
                body,
                document.title(),
                identity,
                metadata,
                tokenizer.tokenize(document.title()),
                tokenizer.tokenize(heading),
                tokenizer.tokenize(identity),
                tokenizer.tokenize(metadata),
                tokenizer.tokenize(body));
    }

    private String slug(String heading) {
        String normalized = tokenizer.normalize(heading);
        StringBuilder slug = new StringBuilder();
        boolean separator = false;
        for (int codePoint : normalized.codePoints().toArray()) {
            if (Character.isLetterOrDigit(codePoint)) {
                if (separator && !slug.isEmpty()) {
                    slug.append('-');
                }
                slug.appendCodePoint(codePoint);
                separator = false;
            } else {
                separator = true;
            }
        }
        return slug.isEmpty() ? "section" : slug.toString();
    }

    private static RankedSection better(RankedSection first, RankedSection second) {
        int comparison = Comparator.comparingInt(RankedSection::priority)
                .thenComparingDouble(RankedSection::lexicalScore)
                .thenComparing(hit -> hit.section().sectionId(), Comparator.reverseOrder())
                .compare(first, second);
        return comparison >= 0 ? first : second;
    }

    private static List<String> significantTerms(List<String> tokens) {
        List<String> distinct = tokens.stream().distinct().toList();
        if (distinct.stream().noneMatch(term -> term.codePointCount(0, term.length()) > 1)) {
            return distinct;
        }
        return distinct.stream()
                .filter(term -> term.codePointCount(0, term.length()) > 1)
                .toList();
    }

    private static String excerpt(String body, String query, List<String> terms) {
        if (body.isBlank()) {
            return "";
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        int match = normalized.indexOf(query);
        int matchLength = query.length();
        if (match < 0) {
            for (String term : terms) {
                match = normalized.indexOf(term);
                if (match >= 0) {
                    matchLength = term.length();
                    break;
                }
            }
        }
        if (match < 0) {
            match = 0;
            matchLength = 1;
        }
        int start = Math.max(0, match - 80);
        int end = Math.min(body.length(), match + Math.max(matchLength, 1) + 160);
        return body.substring(start, end);
    }

    private record IndexedSection(
            KnowledgeDocument document,
            String sectionId,
            String heading,
            String body,
            String titleText,
            String identityText,
            String metadataText,
            List<String> titleTokens,
            List<String> headingTokens,
            List<String> identityTokens,
            List<String> metadataTokens,
            List<String> bodyTokens) {}

    private record RankedSection(
            IndexedSection section,
            int priority,
            double lexicalScore,
            Set<String> matchedFields) {}

    private record Corpus(
            int sectionCount,
            Map<String, Integer> documentFrequencies,
            double averageTitleLength,
            double averageHeadingLength,
            double averageIdentityLength,
            double averageMetadataLength,
            double averageBodyLength) {
        private static Corpus from(List<IndexedSection> sections) {
            Map<String, Integer> frequencies = new HashMap<>();
            double titleLength = 0.0d;
            double headingLength = 0.0d;
            double identityLength = 0.0d;
            double metadataLength = 0.0d;
            double bodyLength = 0.0d;
            for (IndexedSection section : sections) {
                titleLength += section.titleTokens().size();
                headingLength += section.headingTokens().size();
                identityLength += section.identityTokens().size();
                metadataLength += section.metadataTokens().size();
                bodyLength += section.bodyTokens().size();
                Set<String> present = new LinkedHashSet<>();
                present.addAll(section.titleTokens());
                present.addAll(section.headingTokens());
                present.addAll(section.identityTokens());
                present.addAll(section.metadataTokens());
                present.addAll(section.bodyTokens());
                present.forEach(term -> frequencies.merge(term, 1, Integer::sum));
            }
            int count = sections.size();
            return new Corpus(
                    count,
                    Map.copyOf(frequencies),
                    average(titleLength, count),
                    average(headingLength, count),
                    average(identityLength, count),
                    average(metadataLength, count),
                    average(bodyLength, count));
        }

        private double idf(String term) {
            int frequency = documentFrequencies.getOrDefault(term, 0);
            return Math.log(1.0d + (sectionCount - frequency + 0.5d) / (frequency + 0.5d));
        }

        private static double average(double total, int count) {
            return count == 0 ? 1.0d : Math.max(total / count, 1.0d);
        }
    }
}
