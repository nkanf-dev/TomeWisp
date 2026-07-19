package dev.openallay.tool.builtin;

import com.google.gson.JsonElement;
import dev.openallay.agent.tool.ToolDescription;
import dev.openallay.agent.tool.ToolAtLeastOne;
import dev.openallay.agent.tool.ToolOptional;
import dev.openallay.agent.tool.ToolPattern;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.EvidenceBearing;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.RegistryEntrySnapshot;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import dev.openallay.tool.query.QueryOperation;
import dev.openallay.tool.query.RegistryQueryEngine;
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

    @ToolDescription("Search or analyze the captured player-visible game content catalog")
    @ToolAtLeastOne({"query", "pipeline", "describe"})
    public record Input(
            @ToolDescription("Localized name, resource ID, alias, tag, component, or public metadata text")
            @ToolPattern(".*\\S.*") @ToolOptional String query,
            @ToolDescription("Optional catalog kind filter for simple search") @ToolOptional Kind kind,
            @ToolDescription("Virtual dataset used by pipeline; defaults from kind or all")
                    @ToolOptional RegistryQueryEngine.Dataset dataset,
            @ToolDescription("Optional mod namespace scope for schema discovery or pipeline execution")
                    @ToolOptional String namespace,
            @ToolDescription("Discover runtime JSON Pointer fields, types, coverage, examples, and supported operations before querying unfamiliar data")
                    @ToolOptional Boolean describe,
            @ToolDescription("Complete ordered analysis in this one call: filter, rank, group, aggregate, project, and take. For set-level questions, send every stage together and do not issue preliminary per-item searches.")
                    @ToolOptional List<QueryOperation> pipeline) {
        public Input(String query, Kind kind) {
            this(query, kind, null, null, null, null);
        }

        public Input(
                String query,
                Kind kind,
                RegistryQueryEngine.Dataset dataset,
                List<QueryOperation> pipeline) {
            this(query, kind, dataset, null, null, pipeline);
        }
    }

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
            Map<String, JsonElement> properties,
            String provenance) {
        public Match {
            matchedFields = Collections.unmodifiableSet(new TreeSet<>(matchedFields));
            aliases = List.copyOf(aliases);
            tags = Collections.unmodifiableSet(new TreeSet<>(tags));
            components = Collections.unmodifiableSet(new TreeSet<>(components));
            TreeMap<String, JsonElement> copy = new TreeMap<>();
            properties.forEach((key, value) -> copy.put(key, value.deepCopy()));
            properties = Collections.unmodifiableMap(copy);
        }
    }

    public record Output(
            String requestedQuery,
            boolean exists,
            List<Match> matches,
            RegistryQueryEngine.Result analysis,
            RegistryQueryEngine.Schema schema,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            matches = List.copyOf(matches);
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:resolve_resource",
            "Search one named game object, discover typed runtime fields with describe=true, or answer an entire set-level question with one typed pipeline. Unknown mod data is queried by discovered JSON Pointer, not hard-coded field names. Rankings, counts, groups, and comparisons belong in one pipeline call. Use the analyze-game-data Skill first.",
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
        if (input == null || (blank(input.query())
                && !Boolean.TRUE.equals(input.describe())
                && (input.pipeline() == null || input.pipeline().isEmpty()))) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "query or a non-empty pipeline is required");
        }
        if (context.registries().isEmpty()) {
            return new ToolResult.Failure<>(
                    "missing_context", "game content catalog context was not captured for this invocation");
        }

        var snapshot = context.registries().orElseThrow();
        RegistryQueryEngine.Dataset dataset = input.dataset() != null
                ? input.dataset()
                : input.kind() == null
                        ? RegistryQueryEngine.Dataset.all
                        : RegistryQueryEngine.Dataset.valueOf(input.kind().name() + "s");
        if (Boolean.TRUE.equals(input.describe())) {
            RegistryQueryEngine.Schema schema = new RegistryQueryEngine()
                    .describe(snapshot.entries(), dataset, input.namespace());
            return new ToolResult.Success<>(new Output(
                    "", !schema.fields().isEmpty(), List.of(), null, schema,
                    List.of(snapshot.evidence())));
        }
        if (input.pipeline() != null && !input.pipeline().isEmpty()) {
            try {
                List<QueryOperation> operations = new ArrayList<>();
                if (!blank(input.query())) {
                    operations.add(new QueryOperation(
                            QueryOperation.Op.SEARCH, null, null, input.query(), null,
                            null, null, null, null));
                }
                operations.addAll(input.pipeline());
                RegistryQueryEngine.Result analysis = new RegistryQueryEngine()
                        .execute(snapshot.entries(), dataset, input.namespace(), operations);
                return new ToolResult.Success<>(new Output(
                        input.query() == null ? "" : input.query().strip(),
                        !analysis.rows().isEmpty(),
                        List.of(),
                        analysis,
                        null,
                        List.of(snapshot.evidence())));
            } catch (IllegalArgumentException invalid) {
                return new ToolResult.Failure<>("invalid_arguments", invalid.getMessage());
            }
        }
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
        if (estimatedBytes(matches) > 16_384) {
            return new ToolResult.Failure<>(
                    "result_too_large",
                    "Matched " + matches.size()
                            + " rows; use a typed pipeline with FILTER, SELECT, SORT, AGGREGATE, or TAKE");
        }
        return new ToolResult.Success<>(new Output(
                query, !matches.isEmpty(), matches, null, null, List.of(snapshot.evidence())));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int estimatedBytes(List<Match> matches) {
        int bytes = 0;
        for (Match match : matches) {
            bytes += match.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        return bytes;
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
        entry.properties().forEach((key, value) -> {
            metadata.add(key);
            metadata.add(value.toString());
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
                entry.properties(),
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
