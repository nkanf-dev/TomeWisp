package dev.tomewisp.tool.builtin;

import dev.tomewisp.agent.tool.ToolDescription;
import dev.tomewisp.agent.tool.ToolOptional;
import dev.tomewisp.agent.tool.ToolPattern;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.RegistryEntrySnapshot;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class ResolveResourceTool
        implements Tool<ResolveResourceTool.Input, ResolveResourceTool.Output> {
    public enum Kind { item, block, effect, potion, entity, attribute }

    @ToolDescription("Search the captured player-visible game content catalog")
    public record Input(
            @ToolDescription("Localized name, resource ID, alias, tag, component, or public metadata text")
            @ToolPattern(".*\\S.*") String query,
            @ToolDescription("Optional catalog kind filter") @ToolOptional Kind kind) {}

    public record Match(
            String id,
            String kind,
            String displayName,
            String namespace,
            String matchQuality,
            Set<String> matchedFields,
            List<String> aliases,
            Set<String> tags,
            Set<String> components,
            Map<String, String> metadata,
            String provenance) {
        public Match {
            matchedFields = Collections.unmodifiableSet(new TreeSet<>(matchedFields));
            aliases = List.copyOf(aliases);
            tags = Collections.unmodifiableSet(new TreeSet<>(tags));
            components = Collections.unmodifiableSet(new TreeSet<>(components));
            metadata = Collections.unmodifiableMap(new TreeMap<>(metadata));
        }
    }

    public record Output(
            String requestedQuery,
            boolean exists,
            List<Match> matches,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            matches = List.copyOf(matches);
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:resolve_resource",
            "Search the captured game content catalog by localized text, ID, alias, tag, component, or metadata",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY,
            Set.of(ContextCapability.REGISTRIES));

    @Override
    public ToolDescriptor<Input, Output> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        if (input == null || input.query() == null || input.query().isBlank()) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "query must contain player-visible catalog text");
        }
        if (context.registries().isEmpty()) {
            return new ToolResult.Failure<>(
                    "missing_context", "game content catalog context was not captured for this invocation");
        }

        var snapshot = context.registries().orElseThrow();
        String query = input.query().strip();
        Query normalized = new Query(canonical(query), text(query), tokens(text(query)));
        List<Match> matches = snapshot.entries().stream()
                .filter(entry -> input.kind() == null || entry.kind().equals(input.kind().name()))
                .map(entry -> rank(entry, normalized))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(Ranked::rank)
                        .thenComparingInt(Ranked::distance)
                        .thenComparing(ranked -> ranked.entry().id())
                        .thenComparing(ranked -> ranked.entry().kind()))
                .map(ResolveResourceTool::toMatch)
                .toList();
        return new ToolResult.Success<>(new Output(
                query, !matches.isEmpty(), matches, List.of(snapshot.evidence())));
    }

    private static Ranked rank(RegistryEntrySnapshot entry, Query query) {
        Map<String, List<String>> fields = fields(entry);
        String id = canonical(entry.id());
        String path = canonical(path(entry.id()));
        if (id.equals(query.canonical())) {
            return ranked(entry, 0, 0, "exact_id", "id");
        }
        if (text(entry.displayName()).equals(query.text())) {
            return ranked(entry, 10, 0, "exact_name", "displayName");
        }
        if (text(path).equals(query.text())) {
            return ranked(entry, 20, 0, "exact_path", "path");
        }
        Ranked exactField = exactField(entry, query, fields);
        if (exactField != null) {
            return exactField;
        }

        Ranked substring = substring(entry, query, fields);
        if (substring != null) {
            return substring;
        }
        if (!query.tokens().isEmpty()) {
            Set<String> matchedFields = new TreeSet<>();
            boolean all = true;
            for (String token : query.tokens()) {
                boolean tokenMatched = false;
                for (Map.Entry<String, List<String>> field : fields.entrySet()) {
                    if (field.getValue().stream().map(ResolveResourceTool::text)
                            .anyMatch(value -> value.contains(token))) {
                        matchedFields.add(field.getKey());
                        tokenMatched = true;
                    }
                }
                all &= tokenMatched;
            }
            if (all) {
                return new Ranked(
                        entry,
                        200,
                        0,
                        "token_match",
                        Collections.unmodifiableSet(new TreeSet<>(matchedFields)));
            }
        }

        int fuzzyDistance = fuzzyDistance(query, fields);
        if (fuzzyDistance >= 0) {
            return ranked(entry, 300, fuzzyDistance, "fuzzy", "catalogText");
        }
        return null;
    }

    private static Ranked exactField(
            RegistryEntrySnapshot entry, Query query, Map<String, List<String>> fields) {
        List<String> order = List.of("aliases", "tags", "components", "metadata");
        for (int index = 0; index < order.size(); index++) {
            String field = order.get(index);
            boolean exact = fields.get(field).stream().anyMatch(value -> {
                String canonical = canonical(value);
                return canonical.equals(query.canonical())
                        || canonical.strip().replaceFirst("^#", "")
                                .equals(query.canonical().replaceFirst("^#", ""))
                        || text(value).equals(query.text());
            });
            if (exact) {
                return ranked(entry, 30 + index * 10, 0, "exact_" + field, field);
            }
        }
        return null;
    }

    private static Ranked substring(
            RegistryEntrySnapshot entry, Query query, Map<String, List<String>> fields) {
        List<String> order = List.of("displayName", "path", "aliases", "tags", "components", "metadata");
        for (int index = 0; index < order.size(); index++) {
            String field = order.get(index);
            if (fields.get(field).stream().map(ResolveResourceTool::text)
                    .anyMatch(value -> value.contains(query.text()))) {
                return ranked(entry, 100 + index * 10, 0, "substring", field);
            }
        }
        return null;
    }

    private static int fuzzyDistance(Query query, Map<String, List<String>> fields) {
        if (query.tokens().isEmpty()) {
            return -1;
        }
        int total = 0;
        for (String queryToken : query.tokens()) {
            int allowed = allowedDistance(queryToken);
            if (allowed == 0) {
                return -1;
            }
            int best = Integer.MAX_VALUE;
            for (List<String> values : fields.values()) {
                for (String value : values) {
                    for (String candidate : tokens(text(value))) {
                        best = Math.min(best, levenshtein(queryToken, candidate, allowed));
                    }
                }
            }
            if (best > allowed) {
                return -1;
            }
            total += best;
        }
        return total;
    }

    private static Map<String, List<String>> fields(RegistryEntrySnapshot entry) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put("id", List.of(entry.id()));
        fields.put("path", List.of(path(entry.id())));
        fields.put("displayName", List.of(entry.displayName()));
        fields.put("aliases", entry.aliases());
        fields.put("tags", entry.tags().stream().map(tag -> "#" + tag).toList());
        fields.put("components", List.copyOf(entry.components()));
        List<String> metadata = new ArrayList<>();
        entry.metadata().forEach((key, value) -> {
            metadata.add(key);
            metadata.add(value);
            metadata.add(key + " " + value);
        });
        fields.put("metadata", List.copyOf(metadata));
        return fields;
    }

    private static Ranked ranked(
            RegistryEntrySnapshot entry,
            int rank,
            int distance,
            String quality,
            String field) {
        return new Ranked(entry, rank, distance, quality, Set.of(field));
    }

    private static Match toMatch(Ranked ranked) {
        RegistryEntrySnapshot entry = ranked.entry();
        return new Match(
                entry.id(),
                entry.kind(),
                entry.displayName(),
                entry.namespace(),
                ranked.quality(),
                ranked.matchedFields(),
                entry.aliases(),
                entry.tags(),
                entry.components(),
                entry.metadata(),
                entry.provenance());
    }

    private static String canonical(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .strip();
    }

    private static String text(String value) {
        return canonical(value)
                .replace('_', ' ')
                .replace('-', ' ')
                .replace('/', ' ')
                .replace(':', ' ')
                .replace('#', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String path(String id) {
        int namespace = id.indexOf(':');
        return namespace < 0 ? id : id.substring(namespace + 1);
    }

    private static List<String> tokens(String normalized) {
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split(" ")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private static int allowedDistance(String token) {
        int length = token.codePointCount(0, token.length());
        if (length <= 2) return 0;
        if (length <= 5) return 1;
        if (length <= 9) return 2;
        return 3;
    }

    private static int levenshtein(String left, String right, int cutoff) {
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        if (Math.abs(a.length - b.length) > cutoff) {
            return cutoff + 1;
        }
        int[] previous = new int[b.length + 1];
        int[] current = new int[b.length + 1];
        for (int index = 0; index <= b.length; index++) {
            previous[index] = index;
        }
        for (int row = 1; row <= a.length; row++) {
            current[0] = row;
            int rowBest = current[0];
            for (int column = 1; column <= b.length; column++) {
                int replace = previous[column - 1] + (a[row - 1] == b[column - 1] ? 0 : 1);
                current[column] = Math.min(
                        Math.min(previous[column] + 1, current[column - 1] + 1),
                        replace);
                rowBest = Math.min(rowBest, current[column]);
            }
            if (rowBest > cutoff) {
                return cutoff + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length];
    }

    private record Query(String canonical, String text, List<String> tokens) {}

    private record Ranked(
            RegistryEntrySnapshot entry,
            int rank,
            int distance,
            String quality,
            Set<String> matchedFields) {}
}
