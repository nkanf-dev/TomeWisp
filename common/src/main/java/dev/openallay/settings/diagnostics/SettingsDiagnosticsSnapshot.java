package dev.openallay.settings.diagnostics;

import dev.openallay.guide.GuideHistoryPageState;
import dev.openallay.guide.GuideModelMode;
import dev.openallay.guide.GuidePersistenceSnapshot;
import dev.openallay.guide.GuideRequestStatus;
import dev.openallay.guide.GuideTopology;
import dev.openallay.model.config.ModelProtocol;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Friendly cards plus an independently typed, redacted Debug Mode projection. */
public record SettingsDiagnosticsSnapshot(
        List<SettingsDiagnosticCard> cards,
        Optional<DebugSettingsDiagnostics> debug) {
    public SettingsDiagnosticsSnapshot {
        cards = List.copyOf(cards);
        debug = Objects.requireNonNull(debug, "debug");
    }

    public record DebugSettingsDiagnostics(
            long settingsGeneration,
            int databaseSchema,
            List<DebugModelProfile> models,
            DebugCapabilities capabilities,
            Optional<DebugGuide> guide,
            List<DebugSource> sources,
            List<String> failureCodes) {
        public DebugSettingsDiagnostics {
            if (settingsGeneration < 0 || databaseSchema <= 0) {
                throw new IllegalArgumentException("debug generations and schemas are invalid");
            }
            models = List.copyOf(models);
            Objects.requireNonNull(capabilities, "capabilities");
            guide = Objects.requireNonNull(guide, "guide");
            sources = List.copyOf(sources);
            failureCodes = List.copyOf(failureCodes);
            failureCodes.forEach(code -> requireTechnical(code, "failureCode"));
        }
    }

    public record DebugModelProfile(
            String profileId,
            ModelProtocol protocol,
            String endpointAuthority,
            String modelId,
            boolean enabled,
            boolean available,
            boolean credentialPresent,
            Integer effectiveContextWindowTokens,
            boolean metadataPresent) {
        public DebugModelProfile {
            requireTechnical(profileId, "profileId");
            Objects.requireNonNull(protocol, "protocol");
            requireEndpointAuthority(endpointAuthority);
            requireTechnical(modelId, "modelId");
            if (effectiveContextWindowTokens != null && effectiveContextWindowTokens <= 0) {
                throw new IllegalArgumentException("effective context window must be positive");
            }
        }
    }

    public record DebugCapabilities(
            int catalogEntries,
            int availableEntries,
            int enabledEntries,
            int knowledgeSources,
            int tools,
            int skills,
            int configuredRecipeSources,
            int enabledRecipeSources,
            int unknownDisabledEntries) {
        public DebugCapabilities {
            if (catalogEntries < 0 || availableEntries < 0 || enabledEntries < 0
                    || knowledgeSources < 0 || tools < 0 || skills < 0
                    || configuredRecipeSources < 0 || enabledRecipeSources < 0
                    || unknownDisabledEntries < 0) {
                throw new IllegalArgumentException("debug capability counts must not be negative");
            }
            if (availableEntries > catalogEntries
                    || enabledEntries > availableEntries
                    || knowledgeSources + tools + skills != catalogEntries
                    || enabledRecipeSources > configuredRecipeSources) {
                throw new IllegalArgumentException("debug capability counts are inconsistent");
            }
        }
    }

    public record DebugGuide(
            SettingsDiagnosticsAggregator.HistoryScopeKind scopeKind,
            String selectedSessionId,
            GuideModelMode modelMode,
            boolean clientModelAvailable,
            boolean serverModelAvailable,
            GuidePersistenceSnapshot.State persistenceState,
            long submittedGeneration,
            long committedGeneration,
            int pendingWrites,
            boolean deleting,
            long activeRequestCount,
            Optional<DebugRequest> request,
            DebugContext context,
            DebugHistory history) {
        public DebugGuide {
            Objects.requireNonNull(scopeKind, "scopeKind");
            requireTechnical(selectedSessionId, "selectedSessionId");
            Objects.requireNonNull(modelMode, "modelMode");
            Objects.requireNonNull(persistenceState, "persistenceState");
            if (submittedGeneration < 0 || committedGeneration < 0
                    || committedGeneration > submittedGeneration
                    || pendingWrites < 0 || activeRequestCount < 0) {
                throw new IllegalArgumentException("debug Guide counts are invalid");
            }
            request = Objects.requireNonNull(request, "request");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(history, "history");
        }
    }

    public record DebugRequest(
            UUID requestId,
            GuideTopology topology,
            GuideRequestStatus status,
            Long retryAfterMillis,
            int toolCount,
            int sourceCount) {
        public DebugRequest {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(topology, "topology");
            Objects.requireNonNull(status, "status");
            if (retryAfterMillis != null && retryAfterMillis < 0
                    || toolCount < 0 || sourceCount < 0) {
                throw new IllegalArgumentException("debug request counts are invalid");
            }
        }
    }

    public record DebugContext(
            int checkpointCount,
            int successfulCheckpoints,
            int failedCheckpoints,
            long estimatedProjectionTokens) {
        public DebugContext {
            if (checkpointCount < 0 || successfulCheckpoints < 0 || failedCheckpoints < 0
                    || successfulCheckpoints + failedCheckpoints != checkpointCount
                    || estimatedProjectionTokens < 0) {
                throw new IllegalArgumentException("debug context counts are invalid");
            }
        }
    }

    /** Counts only: no actor, scope, request, cursor UUID, transcript, or component payload. */
    public record DebugHistory(
            long loadedRequests,
            long totalRequests,
            Long firstLoadedCount,
            Long lastLoadedCount,
            GuideHistoryPageState pageState,
            long cacheHits,
            long cacheMisses,
            long semanticFallbackCount) {
        public DebugHistory {
            if (loadedRequests < 0 || totalRequests < loadedRequests
                    || firstLoadedCount != null && firstLoadedCount < 0
                    || lastLoadedCount != null && lastLoadedCount < 0
                    || cacheHits < 0 || cacheMisses < 0 || semanticFallbackCount < 0) {
                throw new IllegalArgumentException("debug history counts are invalid");
            }
            Objects.requireNonNull(pageState, "pageState");
            if ((firstLoadedCount == null) != (lastLoadedCount == null)
                    || firstLoadedCount != null && firstLoadedCount > lastLoadedCount) {
                throw new IllegalArgumentException("debug history cursor counts are invalid");
            }
        }
    }

    public record DebugSource(
            String sourceId,
            String generation,
            SettingsDiagnosticsAggregator.SourceState state,
            int itemCount,
            String failureCode) {
        public DebugSource {
            requireTechnical(sourceId, "sourceId");
            if (generation != null) requireTechnical(generation, "generation");
            Objects.requireNonNull(state, "state");
            if (itemCount < 0) {
                throw new IllegalArgumentException("debug source count must not be negative");
            }
            if (failureCode != null) requireTechnical(failureCode, "failureCode");
            boolean available = state == SettingsDiagnosticsAggregator.SourceState.AVAILABLE;
            boolean generated = state == SettingsDiagnosticsAggregator.SourceState.AVAILABLE
                    || state == SettingsDiagnosticsAggregator.SourceState.PARTIAL;
            if (generated != (generation != null) || available != (failureCode == null)) {
                throw new IllegalArgumentException(
                        "debug source state and diagnostics are inconsistent");
            }
        }
    }

    private static void requireTechnical(String value, String name) {
        if (value == null || !value.matches("[a-zA-Z0-9_./:-]{1,160}")) {
            throw new IllegalArgumentException(name + " must be a bounded technical identifier");
        }
        if (containsCredentialVocabulary(value)) {
            throw new IllegalArgumentException(name + " contains forbidden credential vocabulary");
        }
    }

    private static boolean containsCredentialVocabulary(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("authorization")
                || lower.contains("secret")
                || lower.contains("bearer")
                || lower.contains("api_key")
                || lower.contains("apikey")
                || lower.contains("token=")
                || lower.startsWith("sk-")
                || lower.contains("://sk-");
    }

    private static void requireEndpointAuthority(String value) {
        if (value == null || value.length() > 200 || containsCredentialVocabulary(value)) {
            throw new IllegalArgumentException(
                    "endpointAuthority contains forbidden credential vocabulary");
        }
        URI endpoint;
        try {
            endpoint = URI.create(value);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("endpointAuthority must be a redacted URI authority");
        }
        String scheme = endpoint.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || (endpoint.getPath() != null && !endpoint.getPath().isEmpty())) {
            throw new IllegalArgumentException("endpointAuthority must omit credentials and paths");
        }
    }
}
