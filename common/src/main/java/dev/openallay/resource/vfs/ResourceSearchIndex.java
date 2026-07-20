package dev.openallay.resource.vfs;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic text/scalar search built solely from one immutable {@link ResourceView}. */
public final class ResourceSearchIndex {
    public enum Mode { LITERAL, TOKEN }

    public record Request(List<ResourcePath> roots, String query, Mode mode, List<String> fields) {
        public Request {
            roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
            if (roots.isEmpty()) throw new IllegalArgumentException("At least one search root is required");
            if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
            Objects.requireNonNull(mode, "mode");
            fields = fields == null ? List.of() : List.copyOf(fields);
            if (fields.stream().anyMatch(field -> field == null || field.isBlank() || !field.startsWith("/"))) {
                throw new IllegalArgumentException("Search fields must be RFC 6901-style pointers");
            }
        }
    }

    public record Hit(ResourcePath path, String field, String value) implements Comparable<Hit> {
        public Hit {
            Objects.requireNonNull(path, "path");
            if (field == null || field.isBlank()) throw new IllegalArgumentException("field is required");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public int compareTo(Hit other) {
            int pathOrder = path.compareTo(other.path);
            if (pathOrder != 0) return pathOrder;
            int fieldOrder = field.compareTo(other.field);
            return fieldOrder != 0 ? fieldOrder : value.compareTo(other.value);
        }
    }

    public List<Hit> search(ResourceView view, Request request) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(request, "request");
        TreeSet<Hit> hits = new TreeSet<>();
        for (ResourcePath root : request.roots()) {
            view.require(root);
            for (ResourceNode node : view.generation(root.mount()).nodes().values()) {
                ensureActive(view);
                if (!node.path().startsWith(root)) continue;
                ArrayList<TextField> fields = new ArrayList<>();
                collect(node.truth(), "", fields);
                for (TextField field : fields) {
                    if (!request.fields().isEmpty() && !request.fields().contains(field.path())) continue;
                    if (matches(field.value(), request.query(), request.mode())) {
                        hits.add(new Hit(node.path(), field.path(), field.value()));
                    }
                }
            }
        }
        return List.copyOf(hits);
    }

    private static boolean matches(String value, String query, Mode mode) {
        String candidate = canonical(value);
        String expected = canonical(query);
        if (mode == Mode.LITERAL) return candidate.contains(expected);
        Set<String> candidateTokens = tokens(candidate);
        Set<String> queryTokens = tokens(expected);
        return !queryTokens.isEmpty() && candidateTokens.containsAll(queryTokens);
    }

    private static Set<String> tokens(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split("[^\\p{L}\\p{N}_:.-]+")) {
            if (!token.isBlank()) result.add(token);
        }
        return Set.copyOf(result);
    }

    private static String canonical(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static void collect(ResourceValue value, String path, Collection<TextField> target) {
        switch (value) {
            case ResourceValue.Scalar scalar -> {
                if (scalar.value() != null) target.add(new TextField(path.isEmpty() ? "/" : path, scalarText(scalar.value())));
            }
            case ResourceValue.RecordValue record -> record.fields().forEach((key, child) ->
                    collect(child, path + "/" + escape(key), target));
            case ResourceValue.ListValue list -> {
                for (int index = 0; index < list.values().size(); index++) {
                    collect(list.values().get(index), path + "/" + index, target);
                }
            }
            case ResourceValue.TableValue table -> {
                for (int row = 0; row < table.rows().size(); row++) {
                    for (int column = 0; column < table.columns().size(); column++) {
                        collect(table.rows().get(row).get(column), path + "/" + row + "/" + escape(table.columns().get(column)), target);
                    }
                }
            }
            case ResourceValue.DocumentValue document -> {
                target.add(new TextField("/@title", document.title()));
                for (ResourceValue.DocumentSection section : document.sections()) {
                    String base = "/sections/" + escape(section.id());
                    target.add(new TextField(base + "/heading", section.heading()));
                    target.add(new TextField(base + "/text", section.text()));
                }
            }
            case ResourceValue.FailureValue failure -> {
                target.add(new TextField("/code", failure.code()));
                target.add(new TextField("/message", failure.message()));
            }
            case ResourceValue.ReferenceValue reference -> {
                target.add(new TextField("/target", reference.target().toString()));
                target.add(new TextField("/relation", reference.relation()));
            }
            case ResourceValue.BinaryMetadataValue binary -> {
                target.add(new TextField("/mediaType", binary.mediaType()));
                target.add(new TextField("/sha256", binary.sha256()));
            }
            case ResourceValue.DirectoryValue ignored -> { }
        }
    }

    private static String scalarText(Object value) {
        return value instanceof BigDecimal number ? number.stripTrailingZeros().toPlainString() : value.toString();
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static void ensureActive(ResourceView view) {
        if (view.scope().cancelled().getAsBoolean()) {
            throw new ResourceOperationException("agent_cancelled", "Resource search was cancelled");
        }
    }

    private record TextField(String path, String value) { }
}
