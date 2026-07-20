package dev.openallay.tool.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.openallay.agent.tool.ToolDescription;
import dev.openallay.agent.tool.ToolOptional;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.knowledge.online.OnlineKnowledgeAccess;
import dev.openallay.knowledge.online.OnlineKnowledgeDiagnostic;
import dev.openallay.knowledge.online.OnlineKnowledgeResourceSearch;
import dev.openallay.model.CancellationSignal;
import dev.openallay.resource.vfs.ResourceFileSystem;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSearchIndex;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class ResourceGrepTool extends ResourceToolSupport<ResourceGrepTool.Input> {
    @ToolDescription("One indexed literal or token search over selected VFS roots")
    public record Search(
            List<String> roots,
            @ToolDescription("Literal text or complete token set; arbitrary regex is intentionally unsupported") String pattern,
            ResourceSearchIndex.Mode mode,
            @ToolDescription("Optional RFC 6901 fields to search") @ToolOptional List<String> fields) {}

    @ToolDescription("Batch of independent indexed searches")
    public record Input(List<Search> searches, @ToolOptional String cursor) {}

    private static final ToolDescriptor<Input, ResourceToolOutput> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:resource_grep",
            "Search indexed text and scalar fields below one or more OpenAllay VFS roots. Batch independent searches.",
            Input.class, ResourceToolOutput.class, ToolAccess.READ_ONLY, REQUIRED_CONTEXT);
    private final ResourceFileSystem fileSystem;

    public ResourceGrepTool(RequestResourceContext resources) {
        this(resources, new ResourceFileSystem());
    }

    ResourceGrepTool(RequestResourceContext resources, ResourceFileSystem fileSystem) {
        super(resources);
        this.fileSystem = fileSystem;
    }

    @Override public ToolDescriptor<Input, ResourceToolOutput> descriptor() { return DESCRIPTOR; }

    @Override
    protected ToolResult<ResourceToolOutput> execute(
            RequestResourceContext.Session session, ToolInvocationContext context, Input input) {
        if (input != null && input.cursor() != null && !input.cursor().isBlank()) {
            if (input.searches() != null && !input.searches().isEmpty()) {
                return new ToolResult.Failure<>("invalid_arguments", "cursor continuation cannot include new searches");
            }
            return continueCursor(session, context, input.cursor(), GSON.toJson(input));
        }
        if (input == null || input.searches() == null || input.searches().isEmpty()) {
            return new ToolResult.Failure<>("invalid_arguments", "searches must contain at least one initial search");
        }
        ArrayList<ResourceSearchIndex.Request> requests = new ArrayList<>();
        ArrayList<ResourcePath> roots = new ArrayList<>();
        try {
            for (Search search : input.searches()) {
                List<ResourcePath> parsed = search.roots().stream().map(ResourcePath::parse).toList();
                roots.addAll(parsed);
                requests.add(new ResourceSearchIndex.Request(parsed, search.pattern(), search.mode(), search.fields()));
            }
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>("invalid_arguments", failure.getMessage());
        }
        var results = fileSystem.grep(session.view(), requests);
        ArrayList<ResourceToolOutput.Item> items = new ArrayList<>();
        ArrayList<ResourcePath> matches = new ArrayList<>();
        for (ResourceFileSystem.OperationResult<List<ResourceSearchIndex.Hit>> result : results) {
            Search search = input.searches().get(result.inputIndex());
            if (!result.succeeded()) {
                items.add(failure(result.inputIndex(), search.pattern(), result.failure()));
                continue;
            }
            JsonArray hits = new JsonArray();
            result.value().forEach(hit -> {
                JsonObject encoded = new JsonObject();
                encoded.addProperty("path", hit.path().toString());
                encoded.addProperty("field", hit.field());
                encoded.addProperty("value", hit.value());
                hits.add(encoded);
                matches.add(hit.path());
            });
            JsonObject value = new JsonObject();
            value.add("matches", hits);
            items.add(success(result.inputIndex(), search.pattern(), value));
        }
        return publish(session, context, "resource_grep", GSON.toJson(input), items,
                existingInputs(session, roots), matches.stream().distinct().toList(), ResourcePresentation.Kind.TABLE);
    }

    @Override
    protected CompletableFuture<ToolResult<ResourceToolOutput>> executeAsync(
            RequestResourceContext.Session session,
            ToolInvocationContext context,
            Input input,
            CancellationSignal cancellation) {
        if (input == null || input.cursor() != null || input.searches() == null
                || input.searches().isEmpty() || !hasOnlineRoot(input)) {
            return super.executeAsync(session, context, input, cancellation);
        }
        ArrayList<CompletableFuture<OnlineKnowledgeResourceSearch>> pending = new ArrayList<>();
        for (Search search : input.searches()) {
            boolean online = parsedRoots(search).stream().anyMatch(ResourceGrepTool::touchesOnline);
            pending.add(online
                    ? session.onlineKnowledge().search(search.pattern(), cancellation)
                    : CompletableFuture.completedFuture(
                            new OnlineKnowledgeResourceSearch(List.of(), List.of())));
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> publishCombined(
                        session, context, input, pending.stream().map(CompletableFuture::join).toList()));
    }

    private ToolResult<ResourceToolOutput> publishCombined(
            RequestResourceContext.Session session,
            ToolInvocationContext context,
            Input input,
            List<OnlineKnowledgeResourceSearch> onlineResults) {
        ArrayList<ResourceToolOutput.Item> items = new ArrayList<>();
        ArrayList<ResourcePath> roots = new ArrayList<>();
        ArrayList<ResourcePath> primary = new ArrayList<>();
        LinkedHashSet<EvidenceMetadata> evidence = new LinkedHashSet<>();
        boolean anyOnline = false;
        boolean anyOnlineFailure = false;
        for (int index = 0; index < input.searches().size(); index++) {
            Search search = input.searches().get(index);
            List<ResourcePath> parsed = parsedRoots(search);
            roots.addAll(parsed);
            List<ResourcePath> localRoots = parsed.stream()
                    .filter(root -> !root.startsWith(OnlineKnowledgeAccess.ROOT)).toList();
            if (!localRoots.isEmpty()) {
                var local = fileSystem.grep(session.view(), List.of(new ResourceSearchIndex.Request(
                        localRoots, search.pattern(), search.mode(), search.fields()))).getFirst();
                if (local.succeeded()) {
                    JsonArray hits = new JsonArray();
                    local.value().forEach(hit -> {
                        JsonObject encoded = new JsonObject();
                        encoded.addProperty("path", hit.path().toString());
                        encoded.addProperty("field", hit.field());
                        encoded.addProperty("value", hit.value());
                        hits.add(encoded);
                        primary.add(hit.path());
                    });
                    JsonObject value = new JsonObject();
                    value.add("matches", hits);
                    items.add(success(index, search.pattern(), value));
                    evidence.add(session.operationEvidence());
                } else {
                    items.add(failure(index, search.pattern(), local.failure()));
                }
            }

            if (parsed.stream().noneMatch(ResourceGrepTool::touchesOnline)) continue;
            anyOnline = true;
            OnlineKnowledgeResourceSearch online = onlineResults.get(index);
            List<ResourcePath> onlineRoots = parsed.stream().filter(ResourceGrepTool::touchesOnline).toList();
            int returned = 0;
            for (var hit : online.hits()) {
                if (onlineRoots.stream().noneMatch(root -> hit.documentPath().startsWith(root)
                        || OnlineKnowledgeAccess.ROOT.startsWith(root))) {
                    continue;
                }
                JsonObject value = new JsonObject();
                value.addProperty("path", hit.documentPath().toString());
                value.addProperty("field", "/excerpt");
                value.addProperty("title", hit.title());
                value.addProperty("value", hit.excerpt());
                value.addProperty("source_id", hit.sourceId());
                items.add(success(index, search.pattern(), value));
                primary.add(hit.documentPath());
                evidence.add(hit.evidence());
                returned++;
            }
            for (OnlineKnowledgeDiagnostic diagnostic : online.diagnostics()) {
                anyOnlineFailure = true;
                items.add(new ResourceToolOutput.Item(
                        index,
                        search.pattern(),
                        "failure",
                        null,
                        new ResourceToolOutput.Failure(
                                diagnostic.code(),
                                "/knowledge/online/" + safeSource(diagnostic.sourceId()),
                                null,
                                diagnostic.message())));
            }
            if (returned == 0 && online.diagnostics().isEmpty()) {
                JsonObject value = new JsonObject();
                value.addProperty("matches", 0);
                value.addProperty("scope", "/knowledge/online");
                items.add(success(index, search.pattern(), value));
            }
        }
        EvidenceMetadata aggregate = onlineEvidence(context, anyOnlineFailure);
        if (evidence.isEmpty()) evidence.add(anyOnline ? aggregate : session.operationEvidence());
        return publishWithEvidence(
                session,
                context,
                "resource_grep",
                GSON.toJson(input),
                items,
                existingInputs(session, roots),
                primary.stream().distinct().toList(),
                ResourcePresentation.Kind.TABLE,
                anyOnline ? aggregate : session.operationEvidence(),
                List.copyOf(evidence));
    }

    private static boolean hasOnlineRoot(Input input) {
        try {
            return input.searches().stream().flatMap(search -> parsedRoots(search).stream())
                    .anyMatch(ResourceGrepTool::touchesOnline);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static List<ResourcePath> parsedRoots(Search search) {
        Objects.requireNonNull(search, "search");
        if (search.roots() == null || search.roots().isEmpty()) {
            throw new IllegalArgumentException("search roots are required");
        }
        return search.roots().stream().map(ResourcePath::parse).toList();
    }

    private static boolean touchesOnline(ResourcePath root) {
        return root.startsWith(OnlineKnowledgeAccess.ROOT)
                || OnlineKnowledgeAccess.ROOT.startsWith(root);
    }

    private static String safeSource(String sourceId) {
        int separator = sourceId.indexOf(':');
        String value = separator < 0 ? sourceId : sourceId.substring(separator + 1);
        return value.matches("[a-z0-9_.-]+") ? value : "source";
    }

    private static EvidenceMetadata onlineEvidence(
            ToolInvocationContext context, boolean partialFailure) {
        String gameVersion = context.observableGameState()
                .map(snapshot -> snapshot.runtime().gameVersion()).orElse("unknown");
        String loader = context.observableGameState()
                .map(snapshot -> snapshot.runtime().loader()).orElse("unknown");
        return new EvidenceMetadata(
                DataAuthority.INTEGRATION_API,
                DataCompleteness.PARTIAL,
                context.capturedAt(),
                "openallay:online_knowledge_search",
                "openallay:fixed_online_origins",
                gameVersion,
                loader,
                Map.of(
                        "openallay:scope", "public_search",
                        "openallay:source_state", partialFailure ? "partial_failure" : "available"));
    }
}
