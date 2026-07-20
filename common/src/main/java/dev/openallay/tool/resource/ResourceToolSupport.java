package dev.openallay.tool.resource;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.openallay.agent.tool.ToolResultDiagnostics;
import dev.openallay.agent.tool.ToolUiReference;
import dev.openallay.agent.tool.ToolUiSummary;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.context.ContextCapability;
import dev.openallay.model.CancellationSignal;
import dev.openallay.resource.projection.ResourceModelProjector;
import dev.openallay.resource.cursor.ResourceCursor;
import dev.openallay.resource.result.ResourceResultLineage;
import dev.openallay.resource.result.ResourceResultStore;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.resource.vfs.ResourceValues;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

abstract class ResourceToolSupport<I> implements Tool<I, ResourceToolOutput> {
    protected static final Gson GSON = new Gson();
    protected static final java.util.Set<ContextCapability> REQUIRED_CONTEXT = java.util.Set.of(
            ContextCapability.REGISTRIES,
            ContextCapability.RECIPES,
            ContextCapability.PLAYER,
            ContextCapability.OBSERVABLE_GAME_STATE);
    private final RequestResourceContext resources;
    private final ResourceModelProjector projector = new ResourceModelProjector();
    private final AtomicLong invocations = new AtomicLong();

    ResourceToolSupport(RequestResourceContext resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    @Override
    public final ToolResult<ResourceToolOutput> invoke(ToolInvocationContext context, I input) {
        RequestResourceContext.Session session = resources.capture(context, new CancellationSignal());
        return ensurePublishedFailure(session, context, input, execute(session, context, input));
    }

    @Override
    public final CompletableFuture<ToolResult<ResourceToolOutput>> invokeAsync(
            ToolInvocationContext context, I input, CancellationSignal cancellation) {
        cancellation.throwIfCancelled();
        RequestResourceContext.Session session = resources.capture(context, cancellation);
        return executeAsync(session, context, input, cancellation)
                .thenApply(result -> ensurePublishedFailure(session, context, input, result));
    }

    protected abstract ToolResult<ResourceToolOutput> execute(
            RequestResourceContext.Session session, ToolInvocationContext context, I input);

    protected CompletableFuture<ToolResult<ResourceToolOutput>> executeAsync(
            RequestResourceContext.Session session,
            ToolInvocationContext context,
            I input,
            CancellationSignal cancellation) {
        return CompletableFuture.completedFuture(execute(session, context, input));
    }

    private ToolResult<ResourceToolOutput> ensurePublishedFailure(
            RequestResourceContext.Session session,
            ToolInvocationContext context,
            I input,
            ToolResult<ResourceToolOutput> result) {
        if (!(result instanceof ToolResult.Failure<?> failure)) return result;
        String operation = descriptor().id().substring(descriptor().id().indexOf(':') + 1);
        ResourceToolOutput.Item item = new ResourceToolOutput.Item(
                0,
                "arguments",
                "failure",
                null,
                new ResourceToolOutput.Failure(failure.code(), null, null, failure.message()));
        return publish(
                session,
                context,
                operation,
                GSON.toJson(input),
                List.of(item),
                List.of(),
                List.of(),
                ResourcePresentation.Kind.NONE);
    }

    protected final ToolResult<ResourceToolOutput> publish(
            RequestResourceContext.Session session,
            ToolInvocationContext context,
            String operation,
            String canonicalArguments,
            List<ResourceToolOutput.Item> items,
            List<ResourcePath> inputs,
            List<ResourcePath> primaryResources,
            ResourcePresentation.Kind presentationKind) {
        List<dev.openallay.context.EvidenceMetadata> evidence = sourceEvidence(session, inputs);
        dev.openallay.context.EvidenceMetadata resultEvidence = evidence.size() == 1
                ? evidence.getFirst() : session.operationEvidence();
        return publishInternal(session, context, operation, canonicalArguments, items, inputs,
                primaryResources, presentationKind, null,
                resultEvidence, evidence);
    }

    protected final ToolResult<ResourceToolOutput> publishWithEvidence(
            RequestResourceContext.Session session,
            ToolInvocationContext context,
            String operation,
            String canonicalArguments,
            List<ResourceToolOutput.Item> items,
            List<ResourcePath> inputs,
            List<ResourcePath> primaryResources,
            ResourcePresentation.Kind presentationKind,
            dev.openallay.context.EvidenceMetadata resultEvidence,
            List<dev.openallay.context.EvidenceMetadata> evidence) {
        return publishInternal(session, context, operation, canonicalArguments, items, inputs,
                primaryResources, presentationKind, null, resultEvidence, evidence);
    }

    protected final ToolResult<ResourceToolOutput> publishContinuation(
            RequestResourceContext.Session session,
            ToolInvocationContext context,
            String canonicalArguments,
            List<ResourceToolOutput.Item> items,
            ResourceCursor cursor) {
        dev.openallay.context.EvidenceMetadata evidence =
                session.results().require(session.resultScope(), cursor.path()).evidence();
        return publishInternal(
                session,
                context,
                "resource_read",
                canonicalArguments,
                items,
                List.of(cursor.path()),
                List.of(cursor.path()),
                ResourcePresentation.Kind.TABLE,
                cursor,
                evidence,
                List.of(evidence));
    }

    private static List<dev.openallay.context.EvidenceMetadata> sourceEvidence(
            RequestResourceContext.Session session, List<ResourcePath> inputs) {
        java.util.LinkedHashSet<dev.openallay.context.EvidenceMetadata> evidence =
                new java.util.LinkedHashSet<>();
        for (ResourcePath path : inputs) {
            try {
                evidence.add(path.mount().equals("result")
                        ? session.results().require(session.resultScope(), path).evidence()
                        : session.view().require(path).evidence());
            } catch (RuntimeException ignored) {
                // Failed or stale inputs remain represented by the operation evidence and the
                // structured item failure. They never fabricate a factual source.
            }
        }
        if (evidence.isEmpty()) {
            evidence.add(session.operationEvidence());
        }
        return List.copyOf(evidence);
    }

    protected final ToolResult<ResourceToolOutput> continueCursor(
            RequestResourceContext.Session session,
            ToolInvocationContext context,
            String token,
            String canonicalArguments) {
        try {
            var scope = session.view().scope();
            ResourceCursor cursor = session.cursors().resolve(
                    token, scope.actorId(), scope.sessionId(), scope.requestId(), session.view());
            return publishContinuation(
                    session, context, canonicalArguments, resultItems(session, cursor), cursor);
        } catch (RuntimeException failure) {
            String code = failure instanceof dev.openallay.resource.vfs.ResourceOperationException resourceFailure
                    ? resourceFailure.code() : "invalid_cursor";
            return new ToolResult.Failure<>(code, failure.getMessage());
        }
    }

    private ToolResult<ResourceToolOutput> publishInternal(
            RequestResourceContext.Session session,
            ToolInvocationContext context,
            String operation,
            String canonicalArguments,
            List<ResourceToolOutput.Item> items,
            List<ResourcePath> inputs,
            List<ResourcePath> primaryResources,
            ResourcePresentation.Kind presentationKind,
            ResourceCursor continuation,
            dev.openallay.context.EvidenceMetadata resultEvidence,
            List<dev.openallay.context.EvidenceMetadata> evidence) {
        List<ResourcePath> sources = inputs.stream().filter(path -> !path.mount().equals("result")).distinct().toList();
        List<ResourcePath> prior = inputs.stream().filter(path -> path.mount().equals("result")).distinct().toList();
        JsonElement itemJson = GSON.toJsonTree(items);
        ResourceValue truth = ResourceValues.record(java.util.Map.of(
                "operation", operation,
                "items", ResourceValues.fromJson(itemJson)));
        ResourceResultLineage lineage = new ResourceResultLineage(
                sources,
                prior,
                ResourceResultLineage.digestOperation(operation, canonicalArguments));
        String invocationId = context.correlationId() + ':' + operation + ':' + invocations.incrementAndGet();
        ResourceResultStore.Publication publication = new ResourceResultStore.Publication(
                invocationId,
                lineage,
                ResourceKind.RECORD,
                truth,
                resultEvidence,
                new ResourcePresentation(presentationKind, java.util.Map.of()));
        boolean failed = !items.isEmpty() && items.stream().allMatch(item -> item.failure() != null);
        ResourceToolOutput output = session.results().publishBeforeProject(
                session.resultScope(),
                publication,
                path -> exists(session, path),
                record -> {
                    long start = continuation == null ? 0 : continuation.nextPosition();
                    long total = continuation == null ? items.size() : resultItemCount(session, continuation.path());
                    var page = projector.plan(record, 0, start, session.projectionTokenBudget(), total);
                    String nextCursor = null;
                    if (page.cursorEligible()) {
                        var scope = session.view().scope();
                        nextCursor = session.cursors().issue(new ResourceCursor(
                                scope.actorId(), scope.sessionId(), scope.requestId(),
                                scope.connectionGeneration(), stableGenerations(session.view().generationIds()),
                                continuation == null ? lineage.operationDigest() : continuation.queryDigest(),
                                continuation == null ? record.path() : continuation.path(),
                                ResourceCursor.PositionKind.RECORD,
                                page.toExclusive(), null));
                    }
                    var modelView = projector.project(record, page, nextCursor);
                    int exactBytes = GSON.toJson(truth).getBytes(StandardCharsets.UTF_8).length;
                    return new ResourceToolOutput(
                            operation,
                            record.path().toString(),
                            items,
                            evidence,
                            modelView,
                            new ToolUiReference(
                                    record.path(),
                                    primaryResources,
                                    presentationKind,
                                    modelView.receipts().stream().anyMatch(
                                            receipt -> receipt.nextCursor() != null),
                                    uiSummary(operation, items)),
                            new ToolResultDiagnostics(
                                    exactBytes,
                                    modelView.estimatedCharacters(),
                                    record.contentDigest(),
                                    Instant.now()),
                            failed);
                });
        return new ToolResult.Success<>(output);
    }

    private static ToolUiSummary uiSummary(
            String operation, List<ResourceToolOutput.Item> items) {
        int succeeded = 0;
        java.util.TreeSet<String> kinds = new java.util.TreeSet<>();
        for (ResourceToolOutput.Item item : items) {
            if (item.failure() != null) {
                continue;
            }
            succeeded++;
            JsonElement value = item.value();
            if (value != null && value.isJsonObject()) {
                JsonElement kind = value.getAsJsonObject().get("kind");
                if (kind != null && kind.isJsonPrimitive()
                        && kind.getAsJsonPrimitive().isString()) {
                    kinds.add(kind.getAsString());
                }
            }
        }
        return new ToolUiSummary(operation, succeeded, items.size() - succeeded, List.copyOf(kinds));
    }

    private static java.util.Map<String, String> stableGenerations(
            java.util.Map<String, String> generations) {
        java.util.TreeMap<String, String> stable = new java.util.TreeMap<>(generations);
        stable.remove("result");
        return java.util.Map.copyOf(stable);
    }

    protected static List<ResourceToolOutput.Item> resultItems(
            RequestResourceContext.Session session, ResourceCursor cursor) {
        ResourceValue truth = session.results().require(session.resultScope(), cursor.path()).node().truth();
        if (!(truth instanceof ResourceValue.RecordValue record)
                || !(record.fields().get("items") instanceof ResourceValue.ListValue items)) {
            throw new dev.openallay.resource.vfs.ResourceOperationException(
                    "cursor_target_invalid", "Cursor target does not contain pageable result records");
        }
        int start = (int) Math.min(cursor.nextPosition(), items.values().size());
        ArrayList<ResourceToolOutput.Item> page = new ArrayList<>();
        for (ResourceValue value : items.values().subList(start, items.values().size())) {
            page.add(GSON.fromJson(json(value), ResourceToolOutput.Item.class));
        }
        return List.copyOf(page);
    }

    private static long resultItemCount(RequestResourceContext.Session session, ResourcePath path) {
        ResourceValue truth = session.results().require(session.resultScope(), path).node().truth();
        if (truth instanceof ResourceValue.RecordValue record
                && record.fields().get("items") instanceof ResourceValue.ListValue items) {
            return items.values().size();
        }
        return 1;
    }

    protected static ResourceToolOutput.Item success(int index, String input, JsonElement value) {
        return new ResourceToolOutput.Item(index, input, "success", value, null);
    }

    protected static JsonElement json(ResourceValue value) {
        return switch (value) {
            case ResourceValue.Scalar scalar -> scalar.value() == null
                    ? JsonNull.INSTANCE
                    : scalar.value() instanceof Boolean bool
                    ? new JsonPrimitive(bool)
                    : scalar.value() instanceof java.math.BigDecimal number
                    ? new JsonPrimitive(number)
                    : new JsonPrimitive(scalar.value().toString());
            case ResourceValue.RecordValue record -> {
                JsonObject object = new JsonObject();
                record.fields().forEach((key, child) -> object.add(key, json(child)));
                yield object;
            }
            case ResourceValue.ListValue list -> {
                JsonArray array = new JsonArray();
                list.values().forEach(child -> array.add(json(child)));
                yield array;
            }
            case ResourceValue.TableValue table -> {
                JsonObject object = new JsonObject();
                JsonArray columns = new JsonArray();
                table.columns().forEach(columns::add);
                JsonArray rows = new JsonArray();
                table.rows().forEach(row -> {
                    JsonArray encoded = new JsonArray();
                    row.forEach(cell -> encoded.add(json(cell)));
                    rows.add(encoded);
                });
                object.add("columns", columns);
                object.add("rows", rows);
                yield object;
            }
            case ResourceValue.DocumentValue document -> {
                JsonObject object = new JsonObject();
                object.addProperty("title", document.title());
                JsonArray sections = new JsonArray();
                document.sections().forEach(section -> {
                    JsonObject encoded = new JsonObject();
                    encoded.addProperty("id", section.id());
                    encoded.addProperty("heading", section.heading());
                    encoded.addProperty("text", section.text());
                    sections.add(encoded);
                });
                object.add("sections", sections);
                yield object;
            }
            case ResourceValue.DirectoryValue directory -> new JsonPrimitive(directory.childCount());
            case ResourceValue.BinaryMetadataValue binary -> {
                JsonObject object = new JsonObject();
                object.addProperty("size", binary.size());
                object.addProperty("sha256", binary.sha256());
                object.addProperty("media_type", binary.mediaType());
                yield object;
            }
            case ResourceValue.FailureValue failure -> {
                JsonObject object = new JsonObject();
                object.addProperty("code", failure.code());
                object.addProperty("message", failure.message());
                yield object;
            }
            case ResourceValue.ReferenceValue reference -> {
                JsonObject object = new JsonObject();
                object.addProperty("relation", reference.relation());
                object.addProperty("target", reference.target().toString());
                yield object;
            }
        };
    }

    protected static ResourceToolOutput.Item failure(
            int index, String input, dev.openallay.resource.vfs.ResourceOperationFailure failure) {
        return new ResourceToolOutput.Item(index, input, "failure", null,
                new ResourceToolOutput.Failure(
                        failure.code(),
                        failure.path() == null ? null : failure.path().toString(),
                        failure.field(),
                        failure.message()));
    }

    protected static List<ResourcePath> existingInputs(
            RequestResourceContext.Session session, List<ResourcePath> candidates) {
        ArrayList<ResourcePath> result = new ArrayList<>();
        for (ResourcePath path : candidates) {
            if (exists(session, path)) result.add(path);
        }
        return List.copyOf(result);
    }

    private static boolean exists(RequestResourceContext.Session session, ResourcePath path) {
        if (path.mount().equals("result")) {
            try {
                session.results().require(session.resultScope(), path);
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        try {
            session.view().require(path);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
