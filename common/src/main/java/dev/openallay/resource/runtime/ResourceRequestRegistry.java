package dev.openallay.resource.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.openallay.agent.tool.AgentToolResult;
import dev.openallay.agent.tool.ToolResultDiagnostics;
import dev.openallay.agent.tool.ToolUiReference;
import dev.openallay.agent.tool.ToolUiSummary;
import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.RegistrySnapshot;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.agent.context.ContextBudget;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.integration.patchouli.PatchouliMultiblockStore;
import dev.openallay.knowledge.online.OnlineKnowledgeAccess;
import dev.openallay.knowledge.online.OnlineKnowledgeRequestAccess;
import dev.openallay.knowledge.online.OnlineKnowledgeSearchService;
import dev.openallay.platform.PlatformService;
import dev.openallay.resource.mod.ModRawMount;
import dev.openallay.resource.mod.ModResourceSnapshot;
import dev.openallay.resource.mount.GameStateResourceMount;
import dev.openallay.resource.mount.GuideResourceMount;
import dev.openallay.resource.mount.InstalledModResourceMount;
import dev.openallay.resource.mount.KnowledgeResourceMount;
import dev.openallay.resource.mount.OnlineKnowledgeCatalogMount;
import dev.openallay.resource.mount.PlayerResourceMount;
import dev.openallay.resource.mount.RecipeResourceMount;
import dev.openallay.resource.mount.RegistryResourceMount;
import dev.openallay.resource.mount.SkillResourceMount;
import dev.openallay.resource.result.ResourceResultMount;
import dev.openallay.resource.result.ResourceResultLineage;
import dev.openallay.resource.result.ResourceResultStore;
import dev.openallay.resource.projection.ResourceModelProjector;
import dev.openallay.resource.cursor.ResourceCursor;
import dev.openallay.resource.cursor.ResourceCursorStore;
import dev.openallay.resource.vfs.CompositeResourceMount;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourceMountRegistry;
import dev.openallay.resource.vfs.ResourceNode;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValues;
import dev.openallay.resource.vfs.ResourceView;
import dev.openallay.resource.vfs.ResourceViewScope;
import dev.openallay.tool.resource.RequestResourceContext;
import dev.openallay.skill.SkillCatalog;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns live request VFS views and the connection-scoped result store used by the five resource
 * Tools. Minecraft snapshots are already detached when a request is opened; no live object is
 * retained here.
 */
public final class ResourceRequestRegistry implements RequestResourceContext, AutoCloseable {
    private static final List<String> REGISTRY_ROOTS =
            List.of("item", "block", "effect", "potion", "entity", "attribute");

    private final PlatformService platform;
    private final KnowledgeRegistry knowledge;
    private final ResourceResultStore results;
    private final OnlineKnowledgeSearchService onlineKnowledge;
    private final SkillCatalog skills;
    private final PatchouliMultiblockStore patchouliMultiblocks;
    private final Clock clock;
    private final ModResourceSnapshot modResources;
    private final Map<String, RequestState> requests = new ConcurrentHashMap<>();
    private final Map<UUID, Long> actorConnections = new ConcurrentHashMap<>();
    private final AtomicLong nextConnectionGeneration = new AtomicLong(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    public ResourceRequestRegistry(PlatformService platform, KnowledgeRegistry knowledge) {
        this(platform, knowledge, null, null);
    }

    public ResourceRequestRegistry(
            PlatformService platform,
            KnowledgeRegistry knowledge,
            OnlineKnowledgeSearchService onlineKnowledge) {
        this(platform, knowledge, onlineKnowledge, null);
    }

    public ResourceRequestRegistry(
            PlatformService platform,
            KnowledgeRegistry knowledge,
            OnlineKnowledgeSearchService onlineKnowledge,
            SkillCatalog skills) {
        this(platform, knowledge, onlineKnowledge, skills, null);
    }

    public ResourceRequestRegistry(
            PlatformService platform,
            KnowledgeRegistry knowledge,
            OnlineKnowledgeSearchService onlineKnowledge,
            SkillCatalog skills,
            PatchouliMultiblockStore patchouliMultiblocks) {
        this(platform, knowledge, onlineKnowledge, skills,
                patchouliMultiblocks,
                new ResourceResultStore(), Clock.systemUTC(), capture(platform));
    }

    ResourceRequestRegistry(
            PlatformService platform,
            KnowledgeRegistry knowledge,
            OnlineKnowledgeSearchService onlineKnowledge,
            SkillCatalog skills,
            PatchouliMultiblockStore patchouliMultiblocks,
            ResourceResultStore results,
            Clock clock,
            ModResourceSnapshot modResources) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.onlineKnowledge = onlineKnowledge;
        this.skills = skills;
        this.patchouliMultiblocks = patchouliMultiblocks;
        this.results = Objects.requireNonNull(results, "results");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.modResources = Objects.requireNonNull(modResources, "modResources");
    }

    public RequestHandle open(
            UUID actorId,
            String sessionId,
            UUID requestId,
            long connectionGeneration,
            String topology,
            Set<String> capabilities,
            ContextBudget contextBudget,
            ToolInvocationContext context) {
        ensureOpen();
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(contextBudget, "contextBudget");
        if (context.caller().kind() == dev.openallay.context.CallerKind.PLAYER
                && !actorId.equals(context.caller().uuid())) {
            throw new IllegalArgumentException("Resource request actor differs from captured caller");
        }
        String key = context.correlationId();
        AtomicBoolean cancelled = new AtomicBoolean();
        ResourceViewScope viewScope = new ResourceViewScope(
                actorId,
                sessionId,
                requestId.toString(),
                connectionGeneration,
                topology,
                capabilities,
                clock.instant(),
                cancelled::get);
        ResourceResultStore.Scope resultScope =
                new ResourceResultStore.Scope(actorId, sessionId, connectionGeneration);
        results.openScope(resultScope);
        RequestState state = build(context, viewScope, resultScope, cancelled, contextBudget);
        if (requests.putIfAbsent(key, state) != null) {
            state.close();
            throw new IllegalStateException("Resource request already exists: " + key);
        }
        return new RequestHandle(key, state, this);
    }

    @Override
    public Session capture(
            ToolInvocationContext invocation,
            dev.openallay.model.CancellationSignal cancellation) {
        cancellation.throwIfCancelled();
        ensureOpen();
        RequestState state = requests.get(invocation.correlationId());
        if (state == null) {
            throw new IllegalStateException("No Resource VFS view is bound to this Agent request");
        }
        return state.capture();
    }

    public void deleteSession(UUID actorId, String sessionId, long connectionGeneration) {
        results.deleteSession(actorId, sessionId, connectionGeneration);
    }

    /** Returns the stable live connection generation allocated to this actor. */
    public long connectionGeneration(UUID actorId) {
        ensureOpen();
        return actorConnections.computeIfAbsent(
                Objects.requireNonNull(actorId, "actorId"),
                ignored -> nextConnectionGeneration.getAndIncrement());
    }

    /** Ends one actor connection without invalidating unrelated players. */
    public void disconnectActor(UUID actorId) {
        Long generation = actorConnections.remove(Objects.requireNonNull(actorId, "actorId"));
        if (generation != null) {
            disconnect(generation);
        }
    }

    public void disconnect(long connectionGeneration) {
        requests.entrySet().removeIf(entry -> {
            if (entry.getValue().viewScope.connectionGeneration() != connectionGeneration) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
        results.disconnect(connectionGeneration);
    }

    public ResourceResultStore resultStore() {
        return results;
    }

    /**
     * Imports one already validated remote Resource Tool success into the model owner's result
     * scope. The transported normalized JSON remains the exact bridge truth, while the model
     * projection and continuation cursor are rebuilt against the owner's request budget and
     * owner-local {@code /result} mount.
     */
    public AgentToolResult importRemoteResult(
            String requestCorrelationId,
            String toolId,
            String bridgeInvocationId,
            JsonObject normalized,
            String remoteSide) {
        ensureOpen();
        Objects.requireNonNull(requestCorrelationId, "requestCorrelationId");
        Objects.requireNonNull(normalized, "normalized");
        RequestState state = requests.get(requestCorrelationId);
        if (state == null) {
            throw new IllegalStateException("No Resource VFS view is bound to this Agent request");
        }
        return state.importRemote(toolId, bridgeInvocationId, normalized, remoteSide);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        requests.values().forEach(RequestState::close);
        requests.clear();
        actorConnections.clear();
        results.close();
    }

    private RequestState build(
            ToolInvocationContext context,
            ResourceViewScope viewScope,
            ResourceResultStore.Scope resultScope,
            AtomicBoolean cancelled,
            ContextBudget contextBudget) {
        EvidenceMetadata operationEvidence = evidence(context);
        ArrayList<ResourceMount> mounts = new ArrayList<>();
        context.registries().ifPresent(registries -> REGISTRY_ROOTS.forEach(
                root -> mounts.add(new RegistryResourceMount(root, () -> registries))));
        context.recipes().ifPresent(recipes -> mounts.add(new RecipeResourceMount(() -> recipes)));
        context.player().ifPresent(player -> mounts.add(new PlayerResourceMount(() -> player)));
        context.observableGameState().ifPresent(game -> mounts.add(new GameStateResourceMount(() -> game)));

        var installed = context.observableGameState()
                .map(state -> state.mods().installed())
                .orElseGet(this::installedModsOrEmpty);
        EvidenceMetadata modEvidence = context.observableGameState()
                .map(state -> state.mods().evidence())
                .orElse(operationEvidence);
        mounts.add(new CompositeResourceMount(
                dev.openallay.resource.vfs.ResourcePath.of("mod"),
                List.of(
                        new InstalledModResourceMount(() -> installed, () -> modEvidence),
                        new ModRawMount(() -> modResources, () -> modEvidence))));
        if (onlineKnowledge == null) {
            mounts.add(new KnowledgeResourceMount(knowledge::snapshot, patchouliMultiblocks));
        } else {
            mounts.add(new CompositeResourceMount(
                    dev.openallay.resource.vfs.ResourcePath.of("knowledge"),
                    List.of(
                            new KnowledgeResourceMount(knowledge::snapshot, patchouliMultiblocks),
                            new OnlineKnowledgeCatalogMount(
                                    onlineKnowledge.sources(), onlineEvidence(context)))));
        }
        mounts.add(new GuideResourceMount(knowledge::snapshot, patchouliMultiblocks));
        if (skills != null) {
            mounts.add(new SkillResourceMount(() -> skills, platform.gameVersion(), platform.platformName()));
        }

        List<ResourceSnapshot> captured = mounts.stream().map(ResourceMount::snapshot).toList();
        Set<dev.openallay.resource.vfs.ResourcePath> available = captured.stream()
                .flatMap(snapshot -> snapshot.nodes().navigableKeySet().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        ResourceMountRegistry registry = new ResourceMountRegistry();
        for (ResourceSnapshot snapshot : captured) {
            FixedMount fixed = new FixedMount(withResolvedLinks(snapshot, available));
            registry.register(fixed);
            registry.publish(fixed.root().mount());
        }
        ResourceResultMount resultMount = new ResourceResultMount(
                results,
                resultScope,
                operationEvidence,
                path -> path.mount().equals("result") || available.contains(path));
        registry.register(resultMount);
        registry.publish("result");
        ResourceView view = registry.openView(viewScope, Set.of("result"));
        return new RequestState(
                registry,
                resultMount,
                viewScope,
                resultScope,
                operationEvidence,
                cancelled,
                results.records(resultScope).size(),
                view,
                contextBudget.inputTokens(),
                onlineKnowledge == null
                        ? OnlineKnowledgeAccess.unavailable()
                        : new OnlineKnowledgeRequestAccess(onlineKnowledge, context));
    }

    private static ResourceSnapshot withResolvedLinks(
            ResourceSnapshot snapshot, Set<dev.openallay.resource.vfs.ResourcePath> available) {
        TreeMap<dev.openallay.resource.vfs.ResourcePath, ResourceNode> nodes = new TreeMap<>();
        snapshot.nodes().forEach((path, node) -> nodes.put(path, new ResourceNode(
                path,
                node.kind(),
                node.truth(),
                node.children(),
                node.links().stream().filter(link -> available.contains(link.target())).toList(),
                node.evidence(),
                node.presentation())));
        return new ResourceSnapshot(snapshot.root(), snapshot.generationId(), snapshot.capturedAt(), nodes);
    }

    private EvidenceMetadata evidence(ToolInvocationContext context) {
        if (context.observableGameState().isPresent()) {
            return context.observableGameState().orElseThrow().runtime().evidence();
        }
        if (context.registries().isPresent()) {
            return context.registries().orElseThrow().evidence();
        }
        if (context.recipes().isPresent()) {
            return context.recipes().orElseThrow().evidence();
        }
        if (context.player().isPresent()) {
            return context.player().orElseThrow().evidence();
        }
        return new EvidenceMetadata(
                DataAuthority.CLIENT_VISIBLE,
                DataCompleteness.UNKNOWN,
                context.capturedAt(),
                "openallay:request_context",
                "openallay:empty_context",
                platform.gameVersion(),
                platform.platformName(),
                Map.of("openallay:state", "unavailable"));
    }

    private EvidenceMetadata onlineEvidence(ToolInvocationContext context) {
        EvidenceMetadata base = evidence(context);
        return new EvidenceMetadata(
                DataAuthority.INTEGRATION_API,
                DataCompleteness.COMPLETE,
                context.capturedAt(),
                "openallay:online_knowledge_catalog",
                "openallay:fixed_online_origins",
                base.gameVersion(),
                base.loader(),
                Map.of("openallay:scope", "source_catalog"));
    }

    private List<dev.openallay.platform.InstalledModMetadata> installedModsOrEmpty() {
        try {
            return List.copyOf(platform.installedMods());
        } catch (RuntimeException unavailable) {
            // A missing optional loader adapter degrades only /mod. Other detached request
            // mounts remain usable and the evidence already marks this fallback as unknown.
            return List.of();
        }
    }

    private static ModResourceSnapshot capture(PlatformService platform) {
        try {
            return Objects.requireNonNull(platform, "platform").captureModResources();
        } catch (RuntimeException failure) {
            return ModResourceSnapshot.unavailable(
                    Instant.now(), "public_mod_resource_capture_failed");
        }
    }

    private void release(String key, RequestState expected) {
        if (requests.remove(key, expected)) {
            expected.close();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Resource request registry is closed");
        }
    }

    public static final class RequestHandle implements AutoCloseable {
        private final String key;
        private final RequestState state;
        private final ResourceRequestRegistry owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RequestHandle(String key, RequestState state, ResourceRequestRegistry owner) {
            this.key = key;
            this.state = state;
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(key, state);
            }
        }
    }

    private final class RequestState implements AutoCloseable {
        private final ResourceMountRegistry registry;
        private final ResourceResultMount resultMount;
        private final ResourceViewScope viewScope;
        private final ResourceResultStore.Scope resultScope;
        private final EvidenceMetadata operationEvidence;
        private final AtomicBoolean cancelled;
        private final ResourceCursorStore cursors = new ResourceCursorStore();
        private final ResourceView view;
        private final int projectionTokenBudget;
        private final OnlineKnowledgeAccess onlineKnowledge;
        private int resultCount;

        private RequestState(
                ResourceMountRegistry registry,
                ResourceResultMount resultMount,
                ResourceViewScope viewScope,
                ResourceResultStore.Scope resultScope,
                EvidenceMetadata operationEvidence,
                AtomicBoolean cancelled,
                int resultCount,
                ResourceView view,
                int projectionTokenBudget,
                OnlineKnowledgeAccess onlineKnowledge) {
            this.registry = registry;
            this.resultMount = resultMount;
            this.viewScope = viewScope;
            this.resultScope = resultScope;
            this.operationEvidence = operationEvidence;
            this.cancelled = cancelled;
            this.resultCount = resultCount;
            this.view = view;
            this.projectionTokenBudget = projectionTokenBudget;
            this.onlineKnowledge = Objects.requireNonNull(onlineKnowledge, "onlineKnowledge");
        }

        private synchronized Session capture() {
            if (cancelled.get()) {
                throw new IllegalStateException("Resource request has ended");
            }
            int currentCount = results.records(resultScope).size();
            if (currentCount != resultCount) {
                registry.publish(resultMount.root().mount());
                resultCount = currentCount;
            }
            return new Session(
                    view,
                    results,
                    resultScope,
                    operationEvidence,
                    cursors,
                    projectionTokenBudget,
                    onlineKnowledge);
        }

        private synchronized AgentToolResult importRemote(
                String toolId,
                String bridgeInvocationId,
                JsonObject normalized,
                String remoteSide) {
            if (cancelled.get()) {
                throw new IllegalStateException("Resource request has ended");
            }
            String canonicalToolId = requireText(toolId, "toolId");
            String canonicalInvocationId = requireText(bridgeInvocationId, "bridgeInvocationId");
            String canonicalRemoteSide = requireText(remoteSide, "remoteSide");
            JsonObject value = requireRemoteSuccess(normalized);
            String operation = requirePrimitive(value, "operation");
            JsonArray items = requireArray(value, "items");
            JsonArray exactEvidence = requireArray(value, "evidence");
            if (exactEvidence.isEmpty()) {
                throw new IllegalArgumentException("Remote Resource Tool result has no evidence");
            }

            EvidenceMetadata evidence = bridgeEvidence(
                    parseEvidence(exactEvidence.get(0).getAsJsonObject()), canonicalRemoteSide);
            ResourcePresentation presentation = presentation(items);
            ResourceResultLineage lineage = new ResourceResultLineage(
                    List.of(),
                    List.of(),
                    ResourceResultLineage.digestOperation(
                            "bridge_import",
                            canonicalToolId + '\n' + normalized));
            var truth = ResourceValues.record(Map.of(
                    "operation", operation,
                    "items", ResourceValues.fromJson(items)));
            ResourceResultStore.Publication publication = new ResourceResultStore.Publication(
                    "bridge:" + canonicalInvocationId,
                    lineage,
                    ResourceKind.RECORD,
                    truth,
                    evidence,
                    presentation);
            var record = results.publish(resultScope, publication, ignored -> false);
            long total = items.size();
            var projector = new ResourceModelProjector();
            var page = projector.plan(record, 0, 0, projectionTokenBudget, total);
            String nextCursor = null;
            if (page.cursorEligible()) {
                nextCursor = cursors.issue(new ResourceCursor(
                        viewScope.actorId(),
                        viewScope.sessionId(),
                        viewScope.requestId(),
                        viewScope.connectionGeneration(),
                        stableGenerations(view.generationIds()),
                        lineage.operationDigest(),
                        record.path(),
                        ResourceCursor.PositionKind.RECORD,
                        page.toExclusive(),
                        null));
            }
            var modelView = projector.project(record, page, nextCursor);
            ToolUiSummary summary = summary(operation, items);
            ToolUiReference ui = new ToolUiReference(
                    record.path(),
                    primaryResources(items),
                    presentation.kind(),
                    nextCursor != null,
                    summary);
            boolean failed = allItemsFailed(items);
            int normalizedBytes = normalized.toString().getBytes(StandardCharsets.UTF_8).length;
            return new AgentToolResult(
                    canonicalToolId,
                    normalized,
                    modelView,
                    ui,
                    new ToolResultDiagnostics(
                            normalizedBytes,
                            modelView.estimatedCharacters(),
                            record.contentDigest(),
                            clock.instant()),
                    failed);
        }

        @Override
        public synchronized void close() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            view.close();
            cursors.releaseRequest(
                    viewScope.actorId(), viewScope.sessionId(), viewScope.requestId());
            cursors.close();
            onlineKnowledge.close();
        }
    }

    private record FixedMount(ResourceSnapshot snapshot) implements ResourceMount {
        private FixedMount {
            Objects.requireNonNull(snapshot, "snapshot");
        }

        @Override
        public dev.openallay.resource.vfs.ResourcePath root() {
            return snapshot.root();
        }
    }

    private static JsonObject requireRemoteSuccess(JsonObject normalized) {
        if (!normalized.keySet().equals(Set.of("status", "outputType", "value"))
                || !"success".equals(requirePrimitive(normalized, "status"))
                || !normalized.get("value").isJsonObject()) {
            throw new IllegalArgumentException("Remote Resource Tool success envelope is invalid");
        }
        return normalized.getAsJsonObject("value");
    }

    private static String requirePrimitive(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Remote Resource Tool field is invalid: " + field);
        }
        String text = value.getAsString();
        if (text.isBlank()) {
            throw new IllegalArgumentException("Remote Resource Tool field is blank: " + field);
        }
        return text;
    }

    private static JsonArray requireArray(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("Remote Resource Tool field is invalid: " + field);
        }
        return value.getAsJsonArray();
    }

    private static EvidenceMetadata parseEvidence(JsonObject value) {
        JsonObject details = value.has("details") && value.get("details").isJsonObject()
                ? value.getAsJsonObject("details")
                : new JsonObject();
        TreeMap<String, String> decodedDetails = new TreeMap<>();
        details.entrySet().forEach(entry -> {
            if (!entry.getValue().isJsonPrimitive()) {
                throw new IllegalArgumentException("Remote evidence detail is not text");
            }
            decodedDetails.put(entry.getKey(), entry.getValue().getAsString());
        });
        return new EvidenceMetadata(
                DataAuthority.valueOf(requirePrimitive(value, "authority")),
                DataCompleteness.valueOf(requirePrimitive(value, "completeness")),
                Instant.parse(requirePrimitive(value, "capturedAt")),
                requirePrimitive(value, "sourceId"),
                requirePrimitive(value, "provenance"),
                requirePrimitive(value, "gameVersion"),
                requirePrimitive(value, "loader"),
                decodedDetails);
    }

    private static EvidenceMetadata bridgeEvidence(EvidenceMetadata source, String remoteSide) {
        TreeMap<String, String> details = new TreeMap<>(source.details());
        details.put("openallay:bridge_route", remoteSide + "_to_model_owner");
        return new EvidenceMetadata(
                source.authority(),
                source.completeness(),
                source.capturedAt(),
                source.sourceId(),
                source.provenance(),
                source.gameVersion(),
                source.loader(),
                details);
    }

    private static ResourcePresentation presentation(JsonArray items) {
        for (JsonElement itemElement : items) {
            if (!itemElement.isJsonObject()) continue;
            JsonElement valueElement = itemElement.getAsJsonObject().get("value");
            if (valueElement == null || !valueElement.isJsonObject()) continue;
            JsonObject value = valueElement.getAsJsonObject();
            JsonElement kindElement = value.get("presentation");
            if (kindElement == null || !kindElement.isJsonPrimitive()) continue;
            try {
                ResourcePresentation.Kind kind = ResourcePresentation.Kind.valueOf(
                        kindElement.getAsString().toUpperCase(java.util.Locale.ROOT));
                TreeMap<String, String> references = new TreeMap<>();
                JsonElement rawReferences = value.get("presentationReferences");
                if (rawReferences != null && rawReferences.isJsonObject()) {
                    rawReferences.getAsJsonObject().entrySet().forEach(entry -> {
                        if (entry.getValue().isJsonPrimitive()) {
                            references.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    });
                }
                return new ResourcePresentation(kind, references);
            } catch (IllegalArgumentException ignored) {
                // Unknown presentation hints cannot broaden the closed component registry.
            }
        }
        return ResourcePresentation.none();
    }

    private static List<ResourcePath> primaryResources(JsonArray items) {
        ArrayList<ResourcePath> paths = new ArrayList<>();
        for (JsonElement itemElement : items) {
            if (!itemElement.isJsonObject()) continue;
            JsonElement valueElement = itemElement.getAsJsonObject().get("value");
            if (valueElement == null || !valueElement.isJsonObject()) continue;
            JsonElement pathElement = valueElement.getAsJsonObject().get("path");
            if (pathElement == null || !pathElement.isJsonPrimitive()) continue;
            try {
                ResourcePath path = ResourcePath.parse(pathElement.getAsString());
                if (!path.mount().equals("result") && !paths.contains(path)) paths.add(path);
            } catch (RuntimeException ignored) {
                // The exact transported value remains available even if an optional UI hint is bad.
            }
        }
        return List.copyOf(paths);
    }

    private static ToolUiSummary summary(String operation, JsonArray items) {
        int succeeded = 0;
        int failed = 0;
        java.util.TreeSet<String> kinds = new java.util.TreeSet<>();
        for (JsonElement itemElement : items) {
            if (!itemElement.isJsonObject()) continue;
            JsonObject item = itemElement.getAsJsonObject();
            if ("success".equals(optionalText(item, "status"))) succeeded++; else failed++;
            JsonElement valueElement = item.get("value");
            if (valueElement != null && valueElement.isJsonObject()) {
                String kind = optionalText(valueElement.getAsJsonObject(), "kind");
                if (kind != null) kinds.add(kind);
            }
        }
        return new ToolUiSummary(operation, succeeded, failed, List.copyOf(kinds));
    }

    private static boolean allItemsFailed(JsonArray items) {
        if (items.isEmpty()) return false;
        for (JsonElement item : items) {
            if (item.isJsonObject()
                    && "success".equals(optionalText(item.getAsJsonObject(), "status"))) {
                return false;
            }
        }
        return true;
    }

    private static String optionalText(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static Map<String, String> stableGenerations(Map<String, String> generations) {
        TreeMap<String, String> stable = new TreeMap<>(generations);
        stable.remove("result");
        return Map.copyOf(stable);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
