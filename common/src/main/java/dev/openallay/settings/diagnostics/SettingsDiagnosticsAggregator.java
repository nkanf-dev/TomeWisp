package dev.openallay.settings.diagnostics;

import dev.openallay.agent.context.ContextCheckpoint;
import dev.openallay.capability.CapabilityKind;
import dev.openallay.guide.GuideFailure;
import dev.openallay.guide.GuideHistoryPageState;
import dev.openallay.guide.GuideHistoryWindowSnapshot;
import dev.openallay.guide.GuideRequestSnapshot;
import dev.openallay.guide.GuideSessionSnapshot;
import dev.openallay.guide.GuideSnapshot;
import dev.openallay.guide.GuideTimelineEntry;
import dev.openallay.guide.history.GuideHistoryActivity;
import dev.openallay.guide.ui.SemanticLayoutCache;
import dev.openallay.model.config.ModelProfileDefinition;
import dev.openallay.settings.capability.CapabilitySettingsView;
import dev.openallay.settings.capability.RecipeSettingsView;
import dev.openallay.settings.diagnostics.SettingsDiagnosticCard.Domain;
import dev.openallay.settings.diagnostics.SettingsDiagnosticCard.FriendlyStatus;
import dev.openallay.settings.diagnostics.SettingsDiagnosticCard.Metric;
import dev.openallay.settings.diagnostics.SettingsDiagnosticsSnapshot.DebugCapabilities;
import dev.openallay.settings.diagnostics.SettingsDiagnosticsSnapshot.DebugContext;
import dev.openallay.settings.diagnostics.SettingsDiagnosticsSnapshot.DebugGuide;
import dev.openallay.settings.diagnostics.SettingsDiagnosticsSnapshot.DebugHistory;
import dev.openallay.settings.diagnostics.SettingsDiagnosticsSnapshot.DebugModelProfile;
import dev.openallay.settings.diagnostics.SettingsDiagnosticsSnapshot.DebugRequest;
import dev.openallay.settings.diagnostics.SettingsDiagnosticsSnapshot.DebugSettingsDiagnostics;
import dev.openallay.settings.diagnostics.SettingsDiagnosticsSnapshot.DebugSource;
import dev.openallay.settings.model.ModelConnectionResult;
import dev.openallay.settings.model.ModelProfileSettingsView;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/** Pure projection from immutable domain snapshots into friendly and redacted diagnostics. */
public final class SettingsDiagnosticsAggregator {
    public enum HistoryScopeKind {
        NONE,
        SINGLEPLAYER_WORLD,
        MULTIPLAYER_SERVER
    }

    public enum SourceState {
        AVAILABLE,
        PARTIAL,
        UNAVAILABLE,
        FAILED
    }

    public record SourceStatus(
            String sourceId,
            String generation,
            SourceState state,
            int itemCount,
            String failureCode) {
        public SourceStatus {
            sourceId = safeIdentifier(sourceId);
            Objects.requireNonNull(state, "state");
            if (itemCount < 0) {
                throw new IllegalArgumentException("source item count must not be negative");
            }
            if ((state == SourceState.AVAILABLE || state == SourceState.PARTIAL)
                    && (generation == null || generation.isBlank())) {
                throw new IllegalArgumentException("available source requires a generation");
            }
            if ((state == SourceState.UNAVAILABLE || state == SourceState.FAILED)
                    && generation != null) {
                throw new IllegalArgumentException("unavailable source cannot expose a generation");
            }
            generation = generation == null ? null : safeIdentifier(generation);
            failureCode = failureCode == null ? null : safeCode(failureCode);
            if (state == SourceState.AVAILABLE && failureCode != null
                    || state != SourceState.AVAILABLE && failureCode == null) {
                throw new IllegalArgumentException(
                        "source state and failure diagnostic do not agree");
            }
        }
    }

    public record DiagnosticsInputs(
            long settingsGeneration,
            ModelProfileSettingsView models,
            CapabilitySettingsView capabilities,
            RecipeSettingsView recipes,
            Optional<GuideSnapshot> guide,
            GuideHistoryActivity historyActivity,
            HistoryScopeKind historyScopeKind,
            int databaseSchema,
            List<SourceStatus> sources) {
        public DiagnosticsInputs {
            if (settingsGeneration < 0 || databaseSchema <= 0) {
                throw new IllegalArgumentException("diagnostic generations and schemas are invalid");
            }
            Objects.requireNonNull(models, "models");
            Objects.requireNonNull(capabilities, "capabilities");
            Objects.requireNonNull(recipes, "recipes");
            guide = Objects.requireNonNull(guide, "guide");
            Objects.requireNonNull(historyActivity, "historyActivity");
            Objects.requireNonNull(historyScopeKind, "historyScopeKind");
            sources = List.copyOf(sources);
            if (guide.isEmpty() != (historyScopeKind == HistoryScopeKind.NONE)) {
                throw new IllegalArgumentException("history scope kind must match Guide availability");
            }
        }
    }

    public SettingsDiagnosticsSnapshot snapshot(
            boolean debugMode, DiagnosticsInputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        GuideSummary guide = summarizeGuide(inputs.guide());
        List<SettingsDiagnosticCard> cards = List.of(
                modelCard(inputs.models()),
                knowledgeCard(inputs.capabilities(), inputs.sources()),
                historyCard(inputs, guide),
                contextCard(inputs.guide(), guide));
        Optional<DebugSettingsDiagnostics> debug = debugMode
                ? Optional.of(debug(inputs, guide))
                : Optional.empty();
        return new SettingsDiagnosticsSnapshot(cards, debug);
    }

    private static SettingsDiagnosticCard modelCard(ModelProfileSettingsView models) {
        int total = models.profiles().size();
        int available = count(models.profiles().stream().map(
                ModelProfileSettingsView.Profile::available).toList());
        int credentials = count(models.profiles().stream().map(
                ModelProfileSettingsView.Profile::credentialPresent).toList());
        FriendlyStatus status = total == 0 || available == 0
                ? FriendlyStatus.UNAVAILABLE
                : available < total ? FriendlyStatus.ATTENTION : FriendlyStatus.READY;
        return card(Domain.MODELS, status, List.of(
                metric("screen.openallay.settings.diagnostics.metric.configured", total),
                metric("screen.openallay.settings.diagnostics.metric.available", available),
                metric("screen.openallay.settings.diagnostics.metric.credentials", credentials)));
    }

    private static SettingsDiagnosticCard knowledgeCard(
            CapabilitySettingsView capabilities, List<SourceStatus> sources) {
        int catalog = capabilities.catalog().entries().size();
        int enabled = (int) capabilities.catalog().entries().stream()
                .filter(entry -> entry.available() && entry.enabled()).count();
        int availableSources = (int) sources.stream()
                .filter(source -> source.state() == SourceState.AVAILABLE
                        || source.state() == SourceState.PARTIAL)
                .count();
        int total = catalog + sources.size();
        FriendlyStatus status = total == 0
                ? FriendlyStatus.UNAVAILABLE
                : enabled + availableSources == total
                        ? FriendlyStatus.READY
                        : FriendlyStatus.ATTENTION;
        return card(Domain.KNOWLEDGE, status, List.of(
                metric("screen.openallay.settings.diagnostics.metric.registered", catalog),
                metric("screen.openallay.settings.diagnostics.metric.enabled", enabled),
                metric("screen.openallay.settings.diagnostics.metric.sources", sources.size()),
                metric("screen.openallay.settings.diagnostics.metric.available_sources", availableSources)));
    }

    private static SettingsDiagnosticCard historyCard(
            DiagnosticsInputs inputs, GuideSummary guide) {
        FriendlyStatus status;
        if (inputs.guide().isEmpty()) {
            status = FriendlyStatus.NOT_CONNECTED;
        } else {
            GuideSnapshot snapshot = inputs.guide().orElseThrow();
            status = switch (snapshot.persistence().state()) {
                case UNAVAILABLE -> FriendlyStatus.UNAVAILABLE;
                case LOADING, SAVING -> FriendlyStatus.WORKING;
                case DISABLED -> FriendlyStatus.ATTENTION;
                case AVAILABLE -> selectedSession(snapshot)
                                .map(GuideSessionSnapshot::historyWindow)
                                .map(window -> switch (window.state()) {
                                    case LOADING -> FriendlyStatus.WORKING;
                                    case FAILED -> FriendlyStatus.ATTENTION;
                                    case IDLE -> inputs.historyActivity().idleForDeletion()
                                                    && guide.activeRequests() == 0
                                            ? FriendlyStatus.READY : FriendlyStatus.WORKING;
                                })
                                .orElse(FriendlyStatus.READY);
            };
        }
        List<String> notes = new java.util.ArrayList<>();
        if (inputs.guide().isPresent()) {
            notes.add("screen.openallay.settings.diagnostics.history.on_demand");
            selectedSession(inputs.guide().orElseThrow()).ifPresent(session -> {
                if (session.historyWindow().state()
                        == GuideHistoryPageState.LOADING) {
                    notes.add("screen.openallay.settings.diagnostics.history.page_loading");
                } else if (session.historyWindow().state()
                        == GuideHistoryPageState.FAILED) {
                    notes.add("screen.openallay.settings.diagnostics.history.page_failed");
                }
            });
        }
        return card(Domain.HISTORY, status, notes, List.of(
                metric("screen.openallay.settings.diagnostics.metric.pending_writes",
                        inputs.historyActivity().pendingWrites()),
                metric("screen.openallay.settings.diagnostics.metric.active_requests",
                        guide.activeRequests())));
    }

    private static SettingsDiagnosticCard contextCard(
            Optional<GuideSnapshot> guideSnapshot, GuideSummary guide) {
        FriendlyStatus status = guideSnapshot.isEmpty()
                ? FriendlyStatus.NOT_CONNECTED
                : guide.failedCheckpoints() > 0
                        ? FriendlyStatus.ATTENTION
                        : guide.activeRequests() > 0
                                ? FriendlyStatus.WORKING
                                : FriendlyStatus.READY;
        return card(Domain.CONTEXT, status, List.of(
                metric("screen.openallay.settings.diagnostics.metric.checkpoints",
                        guide.checkpointCount()),
                metric("screen.openallay.settings.diagnostics.metric.checkpoint_failures",
                        guide.failedCheckpoints()),
                metric("screen.openallay.settings.diagnostics.metric.estimated_tokens",
                        guide.estimatedProjectionTokens())));
    }

    private static DebugSettingsDiagnostics debug(
            DiagnosticsInputs inputs, GuideSummary summary) {
        List<DebugModelProfile> models = inputs.models().profiles().stream()
                .map(profile -> debugModel(profile))
                .toList();
        int catalog = inputs.capabilities().catalog().entries().size();
        DebugCapabilities capabilities = new DebugCapabilities(
                catalog,
                (int) inputs.capabilities().catalog().entries().stream()
                        .filter(entry -> entry.available()).count(),
                (int) inputs.capabilities().catalog().entries().stream()
                        .filter(entry -> entry.available() && entry.enabled()).count(),
                (int) inputs.capabilities().catalog().entries().stream()
                        .filter(entry -> entry.kind() == CapabilityKind.KNOWLEDGE_SOURCE).count(),
                (int) inputs.capabilities().catalog().entries().stream()
                        .filter(entry -> entry.kind() == CapabilityKind.TOOL).count(),
                (int) inputs.capabilities().catalog().entries().stream()
                        .filter(entry -> entry.kind() == CapabilityKind.SKILL).count(),
                inputs.recipes().sources().size(),
                (int) inputs.recipes().sources().stream()
                        .filter(source -> source.available() && source.enabled()).count(),
                inputs.capabilities().unknownDisabledTools().size()
                        + inputs.capabilities().unknownDisabledSkills().size()
                        + inputs.recipes().unknownDisabledSources().size());
        List<DebugSource> sources = inputs.sources().stream()
                .map(source -> new DebugSource(
                        source.sourceId(),
                        source.generation(),
                        source.state(),
                        source.itemCount(),
                        source.failureCode()))
                .toList();
        return new DebugSettingsDiagnostics(
                inputs.settingsGeneration(),
                inputs.databaseSchema(),
                models,
                capabilities,
                inputs.guide().map(guide -> debugGuide(inputs, guide, summary)),
                sources,
                failureCodes(inputs));
    }

    private static DebugModelProfile debugModel(ModelProfileSettingsView.Profile profile) {
        ModelProfileDefinition definition = profile.definition();
        return new DebugModelProfile(
                safeIdentifier(definition.id()),
                definition.protocol(),
                authority(definition.baseUri()),
                safeIdentifier(definition.model()),
                definition.enabled(),
                profile.available(),
                profile.credentialPresent(),
                profile.effectiveContextWindowTokens(),
                definition.metadata() != null);
    }

    private static DebugGuide debugGuide(
            DiagnosticsInputs inputs, GuideSnapshot guide, GuideSummary summary) {
        Optional<GuideRequestSnapshot> request = selectedSession(guide)
                .flatMap(SettingsDiagnosticsAggregator::latestRequest);
        return new DebugGuide(
                inputs.historyScopeKind(),
                safeIdentifier(guide.selectedSession()),
                guide.modelMode(),
                guide.clientModelAvailable(),
                guide.serverModelAvailable(),
                guide.persistence().state(),
                guide.persistence().submittedGeneration(),
                guide.persistence().committedGeneration(),
                inputs.historyActivity().pendingWrites(),
                inputs.historyActivity().deleting(),
                summary.activeRequests(),
                request.map(value -> new DebugRequest(
                        value.requestId(),
                        value.topology(),
                        value.status(),
                        value.retryAfterMillis(),
                        value.tools().size(),
                        value.sources().size())),
                new DebugContext(
                        summary.checkpointCount(),
                        summary.successfulCheckpoints(),
                        summary.failedCheckpoints(),
                        summary.estimatedProjectionTokens()),
                debugHistory(guide));
    }

    private static DebugHistory debugHistory(GuideSnapshot guide) {
        GuideSessionSnapshot selected = selectedSession(guide).orElse(null);
        SemanticLayoutCache.Stats cache = SemanticLayoutCache.globalStats();
        if (selected == null) {
            return new DebugHistory(0, 0, null, null,
                    GuideHistoryPageState.IDLE,
                    cache.hits(), cache.misses(), 0);
        }
        GuideHistoryWindowSnapshot window = selected.historyWindow();
        long fallbacks = selected.requests().stream()
                .flatMap(request -> request.timeline().stream())
                .filter(GuideTimelineEntry.Assistant.class::isInstance)
                .map(GuideTimelineEntry.Assistant.class::cast)
                .mapToLong(assistant -> assistant.semantic().diagnostics().size())
                .sum();
        return new DebugHistory(
                selected.requests().size(), window.totalRequests(),
                window.firstLoaded() == null ? null : window.firstLoaded().sequence(),
                window.lastLoaded() == null ? null : window.lastLoaded().sequence(),
                window.state(), cache.hits(), cache.misses(), fallbacks);
    }

    private static List<String> failureCodes(DiagnosticsInputs inputs) {
        TreeSet<String> codes = new TreeSet<>();
        for (ModelProfileSettingsView.Profile profile : inputs.models().profiles()) {
            add(codes, profile.failure());
        }
        add(codes, inputs.models().metadataFailure());
        if (inputs.models().connectionResult() instanceof ModelConnectionResult.Failure failure) {
            codes.add(safeCode(failure.code()));
        }
        inputs.guide().ifPresent(guide -> {
            add(codes, guide.persistence().failure());
            for (GuideSessionSnapshot session : guide.sessions()) {
                for (GuideRequestSnapshot request : session.requests()) {
                    add(codes, request.failure());
                }
                for (ContextCheckpoint checkpoint : session.checkpoints()) {
                    if (checkpoint.status() == ContextCheckpoint.Status.FAILED) {
                        codes.add(safeCode(checkpoint.failureCode()));
                    }
                }
            }
        });
        inputs.sources().stream()
                .map(SourceStatus::failureCode)
                .filter(Objects::nonNull)
                .forEach(codes::add);
        return List.copyOf(codes);
    }

    private static GuideSummary summarizeGuide(Optional<GuideSnapshot> optional) {
        if (optional.isEmpty()) return GuideSummary.empty();
        GuideSnapshot guide = optional.orElseThrow();
        long active = guide.sessions().stream()
                .flatMap(session -> session.requests().stream())
                .filter(request -> !request.terminal())
                .count();
        Optional<GuideSessionSnapshot> selected = selectedSession(guide);
        if (selected.isEmpty()) {
            return new GuideSummary(active, 0, 0, 0, 0);
        }
        List<ContextCheckpoint> checkpoints = selected.orElseThrow().checkpoints();
        int successful = (int) checkpoints.stream()
                .filter(value -> value.status() == ContextCheckpoint.Status.SUCCEEDED).count();
        int failed = checkpoints.size() - successful;
        long estimated = checkpoints.stream()
                .mapToLong(ContextCheckpoint::estimatedProjectionTokens)
                .sum();
        return new GuideSummary(active, checkpoints.size(), successful, failed, estimated);
    }

    private static Optional<GuideSessionSnapshot> selectedSession(GuideSnapshot guide) {
        return guide.sessions().stream()
                .filter(session -> session.sessionId().equals(guide.selectedSession()))
                .findFirst();
    }

    private static Optional<GuideRequestSnapshot> latestRequest(GuideSessionSnapshot session) {
        if (session.requests().isEmpty()) return Optional.empty();
        return session.requests().stream()
                .filter(request -> !request.terminal())
                .reduce((first, second) -> second)
                .or(() -> Optional.of(session.requests().getLast()));
    }

    private static SettingsDiagnosticCard card(
            Domain domain, FriendlyStatus status, List<Metric> metrics) {
        return card(domain, status, List.of(), metrics);
    }

    private static SettingsDiagnosticCard card(
            Domain domain, FriendlyStatus status, List<String> noteKeys, List<Metric> metrics) {
        String suffix = domain.name().toLowerCase(Locale.ROOT);
        return new SettingsDiagnosticCard(
                domain,
                status,
                "screen.openallay.settings.diagnostics." + suffix + ".title",
                "screen.openallay.settings.diagnostics.status."
                        + status.name().toLowerCase(Locale.ROOT),
                noteKeys,
                metrics);
    }

    private static Metric metric(String key, long value) {
        return new Metric(key, value);
    }

    private static int count(List<Boolean> values) {
        return (int) values.stream().filter(Boolean::booleanValue).count();
    }

    private static String authority(URI endpoint) {
        String authority = endpoint.getRawAuthority();
        if (authority == null || authority.isBlank()) return "https://redacted.invalid";
        String candidate = endpoint.getScheme().toLowerCase(Locale.ROOT) + "://" + authority;
        String safe = safe(candidate, "");
        return safe.isEmpty() ? "https://redacted.invalid" : safe;
    }

    private static void add(TreeSet<String> codes, GuideFailure failure) {
        if (failure != null) codes.add(safeCode(failure.code()));
    }

    private static String safeCode(String value) {
        return safe(value, "redacted_failure_code");
    }

    private static String safeIdentifier(String value) {
        return safe(value, "redacted");
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank() || value.length() > 160) return fallback;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("authorization")
                || lower.contains("secret")
                || lower.contains("bearer")
                || lower.contains("api_key")
                || lower.contains("apikey")
                || lower.contains("token=")
                || lower.startsWith("sk-")
                || lower.contains("://sk-")) {
            return fallback;
        }
        return value.matches("[a-zA-Z0-9_./:-]+") ? value : fallback;
    }

    private record GuideSummary(
            long activeRequests,
            int checkpointCount,
            int successfulCheckpoints,
            int failedCheckpoints,
            long estimatedProjectionTokens) {
        private static GuideSummary empty() {
            return new GuideSummary(0, 0, 0, 0, 0);
        }
    }
}
