package dev.openallay.resource.vfs;

import dev.openallay.context.EvidenceMetadata;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only operations over one captured ResourceView. */
public final class ResourceFileSystem {
    private final ResourceSearchIndex searchIndex;

    public ResourceFileSystem() {
        this(new ResourceSearchIndex());
    }

    public ResourceFileSystem(ResourceSearchIndex searchIndex) {
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex");
    }

    public record OperationResult<T>(int inputIndex, T value, ResourceOperationFailure failure) {
        public OperationResult {
            if (inputIndex < 0) throw new IllegalArgumentException("inputIndex must be non-negative");
            if ((value == null) == (failure == null)) {
                throw new IllegalArgumentException("Exactly one of value and failure is required");
            }
        }

        public boolean succeeded() {
            return failure == null;
        }
    }

    public record ReadResult(
            ResourcePath path,
            ResourceKind kind,
            ResourceValue value,
            List<ResourceLink> links,
            EvidenceMetadata evidence,
            ResourcePresentation presentation,
            String generationId) {
        public ReadResult {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
            links = List.copyOf(Objects.requireNonNull(links, "links"));
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(presentation, "presentation");
            if (generationId == null || generationId.isBlank()) {
                throw new IllegalArgumentException("generationId is required");
            }
        }
    }

    public List<OperationResult<ResourceDirectoryPage>> list(ResourceView view, List<ResourcePath> paths) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(paths, "paths");
        ArrayList<OperationResult<ResourceDirectoryPage>> results = new ArrayList<>(paths.size());
        for (int index = 0; index < paths.size(); index++) {
            ResourcePath path = paths.get(index);
            try {
                ResourceNode node = view.require(path);
                if (node.kind() != ResourceKind.DIRECTORY && node.children().isEmpty()) {
                    throw new ResourceOperationException("not_a_directory", "Resource has no listable children: " + path);
                }
                results.add(success(index, new ResourceDirectoryPage(
                        path, view.generation(path.mount()).id(), node.children())));
            } catch (RuntimeException failure) {
                results.add(failure(index, path, failure));
            }
        }
        return List.copyOf(results);
    }

    public List<OperationResult<ReadResult>> read(ResourceView view, List<ResourceReadRequest> requests) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(requests, "requests");
        ArrayList<OperationResult<ReadResult>> results = new ArrayList<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            ResourceReadRequest request = requests.get(index);
            try {
                ResourceNode node = view.require(request.path());
                ResourceValue selected = select(node.truth(), request.fields());
                results.add(success(index, new ReadResult(
                        node.path(), node.kind(), selected, node.links(), node.evidence(), node.presentation(),
                        view.generation(node.path().mount()).id())));
            } catch (RuntimeException failure) {
                if (failure instanceof FieldFailure fieldFailure) {
                    results.add(new OperationResult<>(index, null, new ResourceOperationFailure(
                            fieldFailure.code, request.path(), fieldFailure.field, fieldFailure.getMessage())));
                } else {
                    results.add(failure(index, request.path(), failure));
                }
            }
        }
        return List.copyOf(results);
    }

    public List<OperationResult<List<ResourcePath>>> glob(ResourceView view, List<ResourceGlobPattern> patterns) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(patterns, "patterns");
        ArrayList<OperationResult<List<ResourcePath>>> results = new ArrayList<>(patterns.size());
        for (int index = 0; index < patterns.size(); index++) {
            ResourceGlobPattern pattern = patterns.get(index);
            ResourcePath mountPath = ResourcePath.of(pattern.mount());
            try {
                List<ResourcePath> matches = view.generation(pattern.mount()).nodes().navigableKeySet().stream()
                        .peek(ignored -> ensureActive(view))
                        .filter(pattern::matches)
                        .sorted()
                        .toList();
                results.add(success(index, matches));
            } catch (RuntimeException failure) {
                results.add(failure(index, mountPath, failure));
            }
        }
        return List.copyOf(results);
    }

    public List<OperationResult<List<ResourceSearchIndex.Hit>>> grep(
            ResourceView view, List<ResourceSearchIndex.Request> requests) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(requests, "requests");
        ArrayList<OperationResult<List<ResourceSearchIndex.Hit>>> results = new ArrayList<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            ResourceSearchIndex.Request request = requests.get(index);
            ResourcePath path = request.roots().getFirst();
            try {
                results.add(success(index, searchIndex.search(view, request)));
            } catch (RuntimeException failure) {
                results.add(failure(index, path, failure));
            }
        }
        return List.copyOf(results);
    }

    private static ResourceValue select(ResourceValue root, List<String> fields) {
        if (fields.isEmpty()) return root;
        LinkedHashMap<String, ResourceValue> selected = new LinkedHashMap<>();
        for (String field : fields) {
            ResourceValue value = at(root, field);
            if (value == null) {
                throw new FieldFailure("field_unavailable", field, "Resource field is unavailable: " + field);
            }
            selected.put(field, value);
        }
        return new ResourceValue.RecordValue(selected);
    }

    /** Resolve one RFC 6901-style field pointer. Lists accept numeric indices. */
    public static ResourceValue at(ResourceValue root, String pointer) {
        Objects.requireNonNull(root, "root");
        if (pointer == null || pointer.isEmpty() || pointer.equals("/")) return root;
        if (!pointer.startsWith("/")) throw new IllegalArgumentException("Field pointer must begin with '/'");
        ResourceValue current = root;
        for (String raw : pointer.substring(1).split("/", -1)) {
            String part = raw.replace("~1", "/").replace("~0", "~");
            if (current instanceof ResourceValue.RecordValue record) {
                current = record.fields().get(part);
            } else if (current instanceof ResourceValue.ListValue list) {
                try {
                    int index = Integer.parseInt(part);
                    current = index >= 0 && index < list.values().size() ? list.values().get(index) : null;
                } catch (NumberFormatException ignored) {
                    current = null;
                }
            } else {
                return null;
            }
            if (current == null) return null;
        }
        return current;
    }

    private static void ensureActive(ResourceView view) {
        if (view.scope().cancelled().getAsBoolean()) {
            throw new ResourceOperationException("agent_cancelled", "Resource operation was cancelled");
        }
    }

    private static <T> OperationResult<T> success(int index, T value) {
        return new OperationResult<>(index, value, null);
    }

    private static <T> OperationResult<T> failure(int index, ResourcePath path, RuntimeException failure) {
        return new OperationResult<>(index, null, ResourceOperationFailure.from(path, failure));
    }

    private static final class FieldFailure extends RuntimeException {
        private final String code;
        private final String field;

        private FieldFailure(String code, String field, String message) {
            super(message);
            this.code = code;
            this.field = field;
        }
    }
}
