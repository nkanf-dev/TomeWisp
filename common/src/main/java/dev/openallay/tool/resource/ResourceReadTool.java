package dev.openallay.tool.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.openallay.agent.tool.ToolDescription;
import dev.openallay.agent.tool.ToolOptional;
import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.knowledge.online.OnlineKnowledgeDocument;
import dev.openallay.knowledge.online.OnlineKnowledgeException;
import dev.openallay.model.CancellationSignal;
import dev.openallay.resource.vfs.ResourceFileSystem;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceReadRequest;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class ResourceReadTool extends ResourceToolSupport<ResourceReadTool.Input> {
    public enum Format { AUTO, TEXT, RECORDS, TABLE }

    @ToolDescription("Batch exact resource reads with optional relative field selection")
    public record Input(
            @ToolDescription("Absolute VFS paths to read together") List<String> paths,
            @ToolDescription("RFC 6901 field pointers relative to each resource") @ToolOptional List<String> fields,
            @ToolDescription("Preferred model presentation only; exact truth is unchanged") @ToolOptional Format format,
            @ToolDescription("Semantic continuation cursor returned by a previous result") @ToolOptional String cursor) {}

    private static final ToolDescriptor<Input, ResourceToolOutput> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:resource_read",
            "Read exact OpenAllay virtual resources or selected fields. Batch independent paths instead of repeating calls.",
            Input.class, ResourceToolOutput.class, ToolAccess.READ_ONLY, REQUIRED_CONTEXT);
    private final ResourceFileSystem fileSystem;

    public ResourceReadTool(RequestResourceContext resources) {
        this(resources, new ResourceFileSystem());
    }

    ResourceReadTool(RequestResourceContext resources, ResourceFileSystem fileSystem) {
        super(resources);
        this.fileSystem = fileSystem;
    }

    @Override public ToolDescriptor<Input, ResourceToolOutput> descriptor() { return DESCRIPTOR; }

    @Override
    protected ToolResult<ResourceToolOutput> execute(
            RequestResourceContext.Session session, ToolInvocationContext context, Input input) {
        if (input == null) {
            return new ToolResult.Failure<>("invalid_arguments", "provide paths or one semantic cursor");
        }
        boolean hasCursor = input.cursor() != null && !input.cursor().isBlank();
        boolean hasPaths = input.paths() != null && !input.paths().isEmpty();
        if (hasCursor == hasPaths) {
            return new ToolResult.Failure<>("invalid_arguments", "provide exactly one of paths or cursor");
        }
        if (hasCursor) {
            return continueCursor(session, context, input.cursor(), GSON.toJson(input));
        }
        List<ResourcePath> paths;
        List<String> fields = input.fields() == null ? List.of() : input.fields();
        try {
            paths = input.paths().stream().map(ResourcePath::parse).toList();
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>("invalid_resource_path", failure.getMessage());
        }
        List<ResourceReadRequest> requests;
        try {
            requests = paths.stream().map(path -> new ResourceReadRequest(path, fields)).toList();
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>("invalid_arguments", failure.getMessage());
        }
        ArrayList<ResourceToolOutput.Item> items = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            ResourceReadRequest request = requests.get(index);
            String source = input.paths().get(index);
            if (request.path().mount().equals("result")) {
                try {
                    var record = session.results().require(session.resultScope(), request.path());
                    var node = record.node();
                    var selected = select(node.truth(), fields);
                    if (fields.isEmpty() && addPriorResultItems(items, selected)) {
                        continue;
                    }
                    addReadItems(items, index, source, node.path(), node.kind(),
                            record.contentDigest(), selected, node.links(), node.presentation());
                } catch (RuntimeException exception) {
                    items.add(new ResourceToolOutput.Item(index, source, "failure", null,
                            new ResourceToolOutput.Failure(
                                    exception instanceof dev.openallay.resource.vfs.ResourceOperationException resourceFailure
                                            ? resourceFailure.code() : "result_read_failed",
                                    source, null, exception.getMessage())));
                }
                continue;
            }
            ResourceFileSystem.OperationResult<ResourceFileSystem.ReadResult> result =
                    fileSystem.read(session.view(), List.of(request)).getFirst();
            if (!result.succeeded()) {
                items.add(failure(index, source, result.failure()));
                continue;
            }
            ResourceFileSystem.ReadResult read = result.value();
            addReadItems(items, index, source, read.path(), read.kind(), read.generationId(),
                    read.value(), read.links(), read.presentation());
        }
        List<ResourcePath> existing = existingInputs(session, paths);
        return publish(session, context, "resource_read", GSON.toJson(input), items, existing, existing,
                presentation(input.format(), session, existing));
    }

    @Override
    protected CompletableFuture<ToolResult<ResourceToolOutput>> executeAsync(
            RequestResourceContext.Session session,
            ToolInvocationContext context,
            Input input,
            CancellationSignal cancellation) {
        if (input == null || input.cursor() != null || input.paths() == null || input.paths().isEmpty()) {
            return super.executeAsync(session, context, input, cancellation);
        }
        List<ResourcePath> paths;
        try {
            paths = input.paths().stream().map(ResourcePath::parse).toList();
        } catch (RuntimeException ignored) {
            return super.executeAsync(session, context, input, cancellation);
        }
        if (paths.stream().noneMatch(session.onlineKnowledge()::owns)) {
            return super.executeAsync(session, context, input, cancellation);
        }
        ArrayList<CompletableFuture<OnlineReadOutcome>> pending = new ArrayList<>();
        for (ResourcePath path : paths) {
            if (!session.onlineKnowledge().owns(path)) {
                pending.add(CompletableFuture.completedFuture(null));
                continue;
            }
            pending.add(session.onlineKnowledge().read(path, cancellation).handle((document, failure) -> {
                if (failure == null) return OnlineReadOutcome.success(document);
                Throwable cause = unwrap(failure);
                if (cause instanceof CancellationException || cancellation.isCancelled()) {
                    throw new CompletionException(cause);
                }
                return OnlineReadOutcome.failure(cause);
            }));
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> publishMixed(
                        session, context, input, paths,
                        pending.stream().map(CompletableFuture::join).toList()));
    }

    private ToolResult<ResourceToolOutput> publishMixed(
            RequestResourceContext.Session session,
            ToolInvocationContext context,
            Input input,
            List<ResourcePath> paths,
            List<OnlineReadOutcome> online) {
        List<String> fields = input.fields() == null ? List.of() : input.fields();
        ArrayList<ResourceToolOutput.Item> items = new ArrayList<>();
        ArrayList<ResourcePath> primary = new ArrayList<>();
        LinkedHashSet<EvidenceMetadata> evidence = new LinkedHashSet<>();
        boolean onlineFailure = false;
        for (int index = 0; index < paths.size(); index++) {
            ResourcePath path = paths.get(index);
            String source = input.paths().get(index);
            if (session.onlineKnowledge().owns(path)) {
                OnlineReadOutcome outcome = online.get(index);
                if (outcome.failure() != null) {
                    onlineFailure = true;
                    Throwable failure = outcome.failure();
                    String code = failure instanceof OnlineKnowledgeException knowledge
                            ? knowledge.code() : "online_source_unavailable";
                    items.add(argumentFailure(index, source, code, onlineSafeMessage(code)));
                    continue;
                }
                OnlineKnowledgeDocument document = outcome.document();
                evidence.add(document.evidence());
                primary.add(document.path());
                for (OnlineKnowledgeDocument.Section section : document.sections()) {
                    JsonObject value = new JsonObject();
                    value.addProperty("path", document.path().toString());
                    value.addProperty("kind", "document");
                    value.addProperty("source_id", document.sourceId());
                    value.addProperty("title", document.title());
                    value.addProperty("section_id", section.id());
                    value.addProperty("heading", section.heading());
                    value.addProperty("text", section.text());
                    value.addProperty("presentation", "document");
                    JsonObject references = new JsonObject();
                    references.addProperty("sourceId", document.sourceId());
                    references.addProperty("documentPath", document.path().toString());
                    value.add("presentationReferences", references);
                    items.add(success(index, source, value));
                }
                continue;
            }
            ResourceReadRequest request;
            try {
                request = new ResourceReadRequest(path, fields);
            } catch (RuntimeException failure) {
                items.add(argumentFailure(index, source, "invalid_arguments", failure.getMessage()));
                continue;
            }
            if (path.mount().equals("result")) {
                try {
                    var record = session.results().require(session.resultScope(), path);
                    var node = record.node();
                    var selected = select(node.truth(), fields);
                    if (!fields.isEmpty() || !addPriorResultItems(items, selected)) {
                        addReadItems(items, index, source, path, node.kind(), record.contentDigest(),
                                selected, node.links(), node.presentation());
                    }
                    evidence.add(node.evidence());
                    primary.add(path);
                } catch (RuntimeException failure) {
                    String code = failure instanceof dev.openallay.resource.vfs.ResourceOperationException resource
                            ? resource.code() : "result_read_failed";
                    items.add(argumentFailure(index, source, code, failure.getMessage()));
                }
                continue;
            }
            var result = fileSystem.read(session.view(), List.of(request)).getFirst();
            if (!result.succeeded()) {
                items.add(failure(index, source, result.failure()));
                continue;
            }
            var read = result.value();
            addReadItems(items, index, source, read.path(), read.kind(), read.generationId(),
                    read.value(), read.links(), read.presentation());
            evidence.add(read.evidence());
            primary.add(read.path());
        }
        EvidenceMetadata aggregate = onlineEvidence(context, onlineFailure);
        if (evidence.isEmpty()) evidence.add(aggregate);
        return publishWithEvidence(
                session, context, "resource_read", GSON.toJson(input), items,
                existingInputs(session, paths), primary, ResourcePresentation.Kind.DOCUMENT,
                aggregate, List.copyOf(evidence));
    }

    private static ResourceToolOutput.Item argumentFailure(
            int index, String path, String code, String message) {
        return new ResourceToolOutput.Item(index, path, "failure", null,
                new ResourceToolOutput.Failure(code, path, null, message));
    }

    private static EvidenceMetadata onlineEvidence(ToolInvocationContext context, boolean failure) {
        String gameVersion = context.observableGameState()
                .map(snapshot -> snapshot.runtime().gameVersion()).orElse("unknown");
        String loader = context.observableGameState()
                .map(snapshot -> snapshot.runtime().loader()).orElse("unknown");
        return new EvidenceMetadata(
                DataAuthority.INTEGRATION_API,
                DataCompleteness.PARTIAL,
                context.capturedAt(),
                "openallay:online_knowledge_read",
                "openallay:fixed_online_origins",
                gameVersion,
                loader,
                Map.of(
                        "openallay:scope", "public_document",
                        "openallay:source_state", failure ? "partial_failure" : "available"));
    }

    private static String onlineSafeMessage(String code) {
        return switch (code) {
            case "online_document_not_discovered" ->
                    "Search this public knowledge source before reading the document";
            case "online_parse_failed" -> "The public knowledge document could not be read";
            case "online_http_status" -> "The public knowledge source returned an error";
            default -> "The public knowledge source is temporarily unavailable";
        };
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record OnlineReadOutcome(OnlineKnowledgeDocument document, Throwable failure) {
        private static OnlineReadOutcome success(OnlineKnowledgeDocument document) {
            return new OnlineReadOutcome(document, null);
        }

        private static OnlineReadOutcome failure(Throwable failure) {
            return new OnlineReadOutcome(null, failure);
        }
    }

    private static JsonObject encoded(
            ResourcePath path,
            dev.openallay.resource.vfs.ResourceKind kind,
            String generation,
            dev.openallay.resource.vfs.ResourceValue value,
            List<dev.openallay.resource.vfs.ResourceLink> resourceLinks,
            ResourcePresentation presentation) {
        JsonObject encoded = new JsonObject();
        encoded.addProperty("path", path.toString());
        encoded.addProperty("kind", kind.name().toLowerCase());
        encoded.addProperty("generation", generation);
        encoded.add("value", json(value));
        JsonArray links = new JsonArray();
        resourceLinks.forEach(link -> {
            JsonObject item = new JsonObject();
            item.addProperty("relation", link.relation());
            item.addProperty("target", link.target().toString());
            item.addProperty("label", link.label());
            links.add(item);
        });
        encoded.add("links", links);
        encoded.addProperty("presentation", presentation.kind().name().toLowerCase());
        JsonObject references = new JsonObject();
        presentation.references().forEach(references::addProperty);
        encoded.add("presentationReferences", references);
        return encoded;
    }

    private static void addReadItems(
            List<ResourceToolOutput.Item> target,
            int inputIndex,
            String input,
            ResourcePath path,
            dev.openallay.resource.vfs.ResourceKind kind,
            String generation,
            dev.openallay.resource.vfs.ResourceValue value,
            List<dev.openallay.resource.vfs.ResourceLink> links,
            ResourcePresentation presentation) {
        if (value instanceof dev.openallay.resource.vfs.ResourceValue.DocumentValue document
                && documentPages(document).size() > 1) {
            for (var section : documentPages(document)) {
                var page = new dev.openallay.resource.vfs.ResourceValue.DocumentValue(
                        document.title(), List.of(section));
                target.add(success(inputIndex, input,
                        encoded(path, kind, generation, page, links, presentation)));
            }
            return;
        }
        target.add(success(inputIndex, input,
                encoded(path, kind, generation, value, links, presentation)));
    }

    private static List<dev.openallay.resource.vfs.ResourceValue.DocumentSection> documentPages(
            dev.openallay.resource.vfs.ResourceValue.DocumentValue document) {
        ArrayList<dev.openallay.resource.vfs.ResourceValue.DocumentSection> pages = new ArrayList<>();
        for (var section : document.sections()) {
            List<String> lines = section.text().lines().map(String::strip)
                    .filter(line -> !line.isBlank()).toList();
            if (lines.size() <= 1) {
                pages.add(section);
                continue;
            }
            for (int index = 0; index < lines.size(); index++) {
                pages.add(new dev.openallay.resource.vfs.ResourceValue.DocumentSection(
                        section.id() + "-line-" + index,
                        section.heading(),
                        lines.get(index)));
            }
        }
        return List.copyOf(pages);
    }

    private static boolean addPriorResultItems(
            List<ResourceToolOutput.Item> target,
            dev.openallay.resource.vfs.ResourceValue value) {
        if (!(value instanceof dev.openallay.resource.vfs.ResourceValue.RecordValue record)
                || !(record.fields().get("items")
                        instanceof dev.openallay.resource.vfs.ResourceValue.ListValue items)) {
            return false;
        }
        for (var item : items.values()) {
            target.add(GSON.fromJson(json(item), ResourceToolOutput.Item.class));
        }
        return true;
    }

    private static dev.openallay.resource.vfs.ResourceValue select(
            dev.openallay.resource.vfs.ResourceValue value, List<String> fields) {
        if (fields.isEmpty()) return value;
        java.util.LinkedHashMap<String, dev.openallay.resource.vfs.ResourceValue> selected = new java.util.LinkedHashMap<>();
        for (String field : fields) {
            var child = ResourceFileSystem.at(value, field);
            if (child == null) {
                throw new dev.openallay.resource.vfs.ResourceOperationException(
                        "field_unavailable", "Resource field is unavailable: " + field);
            }
            selected.put(field, child);
        }
        return new dev.openallay.resource.vfs.ResourceValue.RecordValue(selected);
    }

    private static ResourcePresentation.Kind presentation(
            Format format,
            RequestResourceContext.Session session,
            List<ResourcePath> paths) {
        if (format == null || format == Format.AUTO) {
            java.util.Set<ResourcePresentation.Kind> kinds = paths.stream()
                    .map(path -> path.mount().equals("result")
                            ? session.results().require(session.resultScope(), path).node().presentation().kind()
                            : session.view().require(path).presentation().kind())
                    .filter(kind -> kind != ResourcePresentation.Kind.NONE)
                    .collect(java.util.stream.Collectors.toSet());
            if (kinds.size() == 1) {
                return kinds.iterator().next();
            }
            return paths.size() > 1 ? ResourcePresentation.Kind.TABLE : ResourcePresentation.Kind.NONE;
        }
        if (format == Format.RECORDS) return ResourcePresentation.Kind.NONE;
        return format == Format.TABLE ? ResourcePresentation.Kind.TABLE : ResourcePresentation.Kind.DOCUMENT;
    }
}
