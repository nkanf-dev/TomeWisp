package dev.openallay.resource.result;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceLink;
import dev.openallay.resource.vfs.ResourceNode;
import dev.openallay.resource.vfs.ResourceOperationException;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceValue;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/** Connection-scoped repository for immutable Tool-result resources. */
public final class ResourceResultStore implements AutoCloseable {
    private final Clock clock;
    private final Map<Scope, ScopeState> scopes = new HashMap<>();
    private final Map<String, ContentBlob> content = new HashMap<>();
    private final java.util.Set<Scope> closedScopes = new java.util.HashSet<>();
    private final java.util.Set<Long> disconnectedGenerations = new java.util.HashSet<>();
    private boolean shutdown;

    public ResourceResultStore() {
        this(Clock.systemUTC());
    }

    public ResourceResultStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void openScope(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        ensureRunning();
        if (closedScopes.contains(scope) || disconnectedGenerations.contains(scope.connectionGeneration())) {
            throw staleScope();
        }
        scopes.computeIfAbsent(scope, ignored -> new ScopeState());
    }

    public ResourceResultRecord publish(Scope scope, Publication publication) {
        return publish(scope, publication, path -> false);
    }

    public synchronized ResourceResultRecord publish(
            Scope scope,
            Publication publication,
            Predicate<ResourcePath> sourceExists) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(sourceExists, "sourceExists");
        ScopeState state = requireOpenScope(scope);
        if (state.invocations.containsKey(publication.invocationId())) {
            throw new ResourceOperationException(
                    "duplicate_result_invocation", "A Tool result is already published for this invocation");
        }
        validateLineage(scope, state, publication.lineage(), sourceExists);

        ResourceResultId id = nextDistinctId(state);
        Content exact = new Content(publication.kind(), publication.truth(), publication.evidence(), publication.presentation());
        String contentDigest = digest(exact);
        ContentBlob blob = content.get(contentDigest);
        if (blob == null) {
            blob = new ContentBlob(exact);
            content.put(contentDigest, blob);
        } else if (!blob.value.equals(exact)) {
            throw new IllegalStateException("SHA-256 collision between distinct Resource results");
        }
        blob.references++;

        ResourceNode node = new ResourceNode(
                id.path(),
                blob.value.kind(),
                blob.value.truth(),
                List.of(),
                links(publication.lineage()),
                blob.value.evidence(),
                blob.value.presentation());
        ResourceResultRecord record = new ResourceResultRecord(
                id,
                scope,
                publication.invocationId(),
                publication.lineage(),
                contentDigest,
                node,
                clock.instant());
        state.records.put(id, record);
        state.invocations.put(publication.invocationId(), id);
        state.revision++;
        return record;
    }

    /** Calls the projector only after the exact record is visible through {@link #require}. */
    public <T> T publishBeforeProject(
            Scope scope,
            Publication publication,
            Predicate<ResourcePath> sourceExists,
            Function<ResourceResultRecord, T> projector) {
        Objects.requireNonNull(projector, "projector");
        ResourceResultRecord record = publish(scope, publication, sourceExists);
        return projector.apply(record);
    }

    public synchronized ResourceResultRecord require(Scope scope, ResourceResultId id) {
        ScopeState state = requireOpenScope(scope);
        ResourceResultRecord record = state.records.get(Objects.requireNonNull(id, "id"));
        if (record == null) {
            boolean belongsToAnotherOwner = scopes.entrySet().stream()
                    .anyMatch(entry -> !entry.getKey().equals(scope) && entry.getValue().records.containsKey(id));
            throw belongsToAnotherOwner ? forbidden() : staleResource();
        }
        return record;
    }

    public ResourceResultRecord require(Scope scope, ResourcePath path) {
        return require(scope, ResourceResultId.fromPath(path));
    }

    public synchronized List<ResourceResultRecord> records(Scope scope) {
        return List.copyOf(requireOpenScope(scope).records.values());
    }

    synchronized SnapshotData snapshotData(Scope scope) {
        ScopeState state = requireOpenScope(scope);
        return new SnapshotData(state.revision, List.copyOf(state.records.values()));
    }

    public synchronized void deleteSession(UUID actorId, String sessionId, long connectionGeneration) {
        Objects.requireNonNull(actorId, "actorId");
        requireText(sessionId, "sessionId");
        releaseMatching(scope -> scope.actorId().equals(actorId)
                && scope.sessionId().equals(sessionId)
                && scope.connectionGeneration() == connectionGeneration, true);
    }

    public synchronized void disconnect(long connectionGeneration) {
        if (connectionGeneration < 0) {
            throw new IllegalArgumentException("connectionGeneration must be non-negative");
        }
        releaseMatching(scope -> scope.connectionGeneration() == connectionGeneration, true);
        disconnectedGenerations.add(connectionGeneration);
    }

    public synchronized int contentObjectCount() {
        return content.size();
    }

    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized void close() {
        if (shutdown) {
            return;
        }
        for (ScopeState state : scopes.values()) {
            release(state);
        }
        scopes.clear();
        content.clear();
        closedScopes.clear();
        disconnectedGenerations.clear();
        shutdown = true;
    }

    private void validateLineage(
            Scope scope,
            ScopeState state,
            ResourceResultLineage lineage,
            Predicate<ResourcePath> sourceExists) {
        for (ResourcePath source : lineage.sourcePaths()) {
            if (!sourceExists.test(source)) {
                throw new ResourceOperationException(
                        "unpublished_lineage", "Result source is not present in the captured resource view: " + source);
            }
        }
        for (ResourcePath priorPath : lineage.priorResultPaths()) {
            ResourceResultId priorId = ResourceResultId.fromPath(priorPath);
            ResourceResultRecord prior = state.records.get(priorId);
            if (prior == null || !prior.scope().equals(scope)) {
                throw new ResourceOperationException(
                        "unpublished_lineage", "Prior result is not published in this live result scope");
            }
        }
    }

    private static List<ResourceLink> links(ResourceResultLineage lineage) {
        ArrayList<ResourceLink> links = new ArrayList<>();
        for (ResourcePath path : lineage.sourcePaths()) {
            links.add(new ResourceLink("source", path, path.toString()));
        }
        for (ResourcePath path : lineage.priorResultPaths()) {
            links.add(new ResourceLink("derived_from", path, path.toString()));
        }
        return List.copyOf(links);
    }

    private ResourceResultId nextDistinctId(ScopeState state) {
        ResourceResultId id;
        do {
            id = ResourceResultId.create();
        } while (state.records.containsKey(id));
        return id;
    }

    private ScopeState requireOpenScope(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        ensureRunning();
        ScopeState state = scopes.get(scope);
        if (state == null || disconnectedGenerations.contains(scope.connectionGeneration())) {
            throw staleScope();
        }
        return state;
    }

    private void ensureRunning() {
        if (shutdown) {
            throw staleScope();
        }
    }

    private void releaseMatching(Predicate<Scope> predicate, boolean tombstone) {
        ArrayList<Scope> removed = new ArrayList<>();
        scopes.forEach((scope, state) -> {
            if (predicate.test(scope)) {
                release(state);
                removed.add(scope);
            }
        });
        if (tombstone) {
            closedScopes.addAll(removed);
        }
        removed.forEach(scopes::remove);
    }

    private void release(ScopeState state) {
        for (ResourceResultRecord record : state.records.values()) {
            ContentBlob blob = content.get(record.contentDigest());
            if (blob != null && --blob.references == 0) {
                content.remove(record.contentDigest());
            }
        }
        state.records.clear();
        state.invocations.clear();
    }

    private static ResourceOperationException staleScope() {
        return staleResource();
    }

    private static ResourceOperationException staleResource() {
        return new ResourceOperationException("stale_resource", "The live Tool-result resource has expired");
    }

    private static ResourceOperationException forbidden() {
        return new ResourceOperationException("resource_forbidden", "Resource is outside the active owner scope");
    }

    private static String digest(Content content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            CanonicalHash hash = new CanonicalHash(digest);
            hash.text(content.kind().name());
            hash.value(content.truth());
            EvidenceMetadata evidence = content.evidence();
            hash.text(evidence.authority().name());
            hash.text(evidence.completeness().name());
            hash.text(evidence.capturedAt().toString());
            hash.text(evidence.sourceId());
            hash.text(evidence.provenance());
            hash.text(evidence.gameVersion());
            hash.text(evidence.loader());
            new TreeMap<>(evidence.details()).forEach((key, value) -> {
                hash.text(key);
                hash.text(value);
            });
            hash.text(content.presentation().kind().name());
            new TreeMap<>(content.presentation().references()).forEach((key, value) -> {
                hash.text(key);
                hash.text(value);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    public record Scope(UUID actorId, String sessionId, long connectionGeneration) {
        public Scope {
            Objects.requireNonNull(actorId, "actorId");
            sessionId = requireText(sessionId, "sessionId");
            if (connectionGeneration < 0) {
                throw new IllegalArgumentException("connectionGeneration must be non-negative");
            }
        }
    }

    public record Publication(
            String invocationId,
            ResourceResultLineage lineage,
            ResourceKind kind,
            ResourceValue truth,
            EvidenceMetadata evidence,
            ResourcePresentation presentation) {
        public Publication {
            invocationId = requireText(invocationId, "invocationId");
            Objects.requireNonNull(lineage, "lineage");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(truth, "truth");
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(presentation, "presentation");
            if (!kindMatches(kind, truth)) {
                throw new IllegalArgumentException("Result content kind does not match its exact truth value");
            }
        }

        private static boolean kindMatches(ResourceKind kind, ResourceValue truth) {
            return switch (kind) {
                case DIRECTORY -> truth instanceof ResourceValue.DirectoryValue;
                case SCALAR -> truth instanceof ResourceValue.Scalar;
                case RECORD -> truth instanceof ResourceValue.RecordValue;
                case LIST -> truth instanceof ResourceValue.ListValue;
                case TABLE -> truth instanceof ResourceValue.TableValue;
                case DOCUMENT -> truth instanceof ResourceValue.DocumentValue;
                case BINARY_METADATA -> truth instanceof ResourceValue.BinaryMetadataValue;
                case FAILURE -> truth instanceof ResourceValue.FailureValue;
                case REFERENCE -> truth instanceof ResourceValue.ReferenceValue;
            };
        }
    }

    record SnapshotData(long revision, List<ResourceResultRecord> records) {}

    private record Content(
            ResourceKind kind,
            ResourceValue truth,
            EvidenceMetadata evidence,
            ResourcePresentation presentation) {}

    private static final class ContentBlob {
        private final Content value;
        private int references;

        private ContentBlob(Content value) {
            this.value = value;
        }
    }

    private static final class ScopeState {
        private final Map<ResourceResultId, ResourceResultRecord> records = new LinkedHashMap<>();
        private final Map<String, ResourceResultId> invocations = new HashMap<>();
        private long revision;
    }

    private static final class CanonicalHash {
        private final MessageDigest digest;

        private CanonicalHash(MessageDigest digest) {
            this.digest = digest;
        }

        private void text(String value) {
            byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }

        private void value(ResourceValue value) {
            switch (value) {
                case ResourceValue.Scalar scalar -> {
                    text("scalar");
                    Object raw = scalar.value();
                    if (raw == null) {
                        text("null");
                    } else if (raw instanceof BigDecimal number) {
                        text("number");
                        text(number.stripTrailingZeros().toPlainString());
                    } else {
                        text(raw.getClass().getSimpleName());
                        text(raw.toString());
                    }
                }
                case ResourceValue.RecordValue record -> {
                    text("record");
                    new TreeMap<>(record.fields()).forEach((key, field) -> {
                        text(key);
                        value(field);
                    });
                }
                case ResourceValue.ListValue list -> {
                    text("list");
                    list.values().forEach(this::value);
                }
                case ResourceValue.TableValue table -> {
                    text("table");
                    table.columns().forEach(this::text);
                    table.rows().forEach(row -> row.forEach(this::value));
                }
                case ResourceValue.DocumentValue document -> {
                    text("document");
                    text(document.title());
                    document.sections().forEach(section -> {
                        text(section.id());
                        text(section.heading());
                        text(section.text());
                    });
                }
                case ResourceValue.DirectoryValue directory -> {
                    text("directory");
                    text(Integer.toString(directory.childCount()));
                }
                case ResourceValue.BinaryMetadataValue binary -> {
                    text("binary");
                    text(Long.toString(binary.size()));
                    text(binary.sha256());
                    text(binary.mediaType());
                }
                case ResourceValue.FailureValue failure -> {
                    text("failure");
                    text(failure.code());
                    text(failure.message());
                }
                case ResourceValue.ReferenceValue reference -> {
                    text("reference");
                    text(reference.relation());
                    text(reference.target().toString());
                }
            }
        }
    }
}
