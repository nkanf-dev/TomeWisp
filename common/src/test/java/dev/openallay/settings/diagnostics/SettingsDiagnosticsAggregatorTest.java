package dev.openallay.settings.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.openallay.agent.context.ContextCheckpoint;
import dev.openallay.capability.CapabilityCatalogSnapshot;
import dev.openallay.capability.CapabilityKind;
import dev.openallay.capability.CapabilityPolicy;
import dev.openallay.capability.CapabilitySettingsEntry;
import dev.openallay.guide.GuideMessage;
import dev.openallay.guide.GuideHistoryPageState;
import dev.openallay.guide.GuideHistoryWindowSnapshot;
import dev.openallay.guide.GuideFailure;
import dev.openallay.guide.GuideModelMode;
import dev.openallay.guide.GuidePersistenceSnapshot;
import dev.openallay.guide.GuideRequestSnapshot;
import dev.openallay.guide.GuideRequestStatus;
import dev.openallay.guide.GuideSessionSnapshot;
import dev.openallay.guide.GuideSnapshot;
import dev.openallay.guide.GuideTimelineEntry;
import dev.openallay.guide.GuideToolActivity;
import dev.openallay.guide.GuideToolMessage;
import dev.openallay.guide.GuideToolStatus;
import dev.openallay.guide.GuideTopology;
import dev.openallay.guide.history.GuideHistoryActivity;
import dev.openallay.guide.history.GuideHistoryPartition;
import dev.openallay.guide.history.GuideHistoryCursor;
import dev.openallay.model.ModelUsage;
import dev.openallay.model.config.ModelProfileDefinition;
import dev.openallay.model.config.ModelProfilesConfig;
import dev.openallay.model.config.ModelProtocol;
import dev.openallay.settings.capability.CapabilitySettingsView;
import dev.openallay.settings.capability.RecipeSettingsView;
import dev.openallay.settings.diagnostics.SettingsDiagnosticCard.Domain;
import dev.openallay.settings.diagnostics.SettingsDiagnosticCard.FriendlyStatus;
import dev.openallay.settings.model.ModelProfileSettingsView;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SettingsDiagnosticsAggregatorTest {
    private static final UUID ACTOR =
            UUID.fromString("35e65d67-6122-4e39-b19d-43f7d042b1f6");
    private static final UUID REQUEST =
            UUID.fromString("841bbf60-7973-463a-a56c-7485f9537c68");

    @Test
    void normalDiagnosticsCannotRepresentTechnicalOrSecretFields() {
        SettingsDiagnosticsSnapshot normal =
                new SettingsDiagnosticsAggregator().snapshot(false, inputs());

        String rendered = normal.toString().toLowerCase();
        assertFalse(rendered.contains(REQUEST.toString()));
        assertFalse(rendered.contains(ACTOR.toString()));
        assertFalse(rendered.contains("scope_id"));
        assertFalse(rendered.contains("secret-value"));
        assertFalse(rendered.contains("authorization"));
        assertFalse(rendered.contains("reasoning"));
        assertTrue(normal.cards().stream()
                .allMatch(card -> card.friendlyStatus() != null));
        assertTrue(normal.debug().isEmpty());
    }

    @Test
    void debugAddsOnlyApprovedRedactedTechnicalProjection() {
        SettingsDiagnosticsSnapshot debug =
                new SettingsDiagnosticsAggregator().snapshot(true, inputs());

        SettingsDiagnosticsSnapshot.DebugSettingsDiagnostics technical =
                debug.debug().orElseThrow();
        assertEquals(GuideHistoryPartition.SCHEMA_VERSION, technical.databaseSchema());
        assertEquals("https://provider.example:8443",
                technical.models().getFirst().endpointAuthority());
        assertEquals(REQUEST,
                technical.guide().orElseThrow().request().orElseThrow().requestId());
        assertEquals(GuideRequestStatus.RATE_LIMITED,
                technical.guide().orElseThrow().request().orElseThrow().status());
        assertEquals(2_000L,
                technical.guide().orElseThrow().request().orElseThrow().retryAfterMillis());
        assertEquals(1, technical.guide().orElseThrow().context().checkpointCount());
        assertEquals(321L,
                technical.guide().orElseThrow().context().estimatedProjectionTokens());
        assertEquals("generation-7", technical.sources().getFirst().generation());
        assertFalse(technical.models().getFirst().metadataPresent());
        assertEquals(1, technical.capabilities().tools());
        assertEquals(1, technical.guide().orElseThrow().history().loadedRequests());
        assertEquals(1, technical.guide().orElseThrow().history().totalRequests());
        assertTrue(technical.guide().orElseThrow().history().cacheHits() >= 0);
        assertTrue(technical.guide().orElseThrow().history().cacheMisses() >= 0);

        String rendered = debug.toString().toLowerCase();
        assertFalse(rendered.contains("/v1/private"));
        assertFalse(rendered.contains("/users/player/history.sqlite3"));
        assertFalse(rendered.contains(ACTOR.toString()));
        assertFalse(rendered.contains("secret-value"));
        assertFalse(rendered.contains("authorization"));
        assertFalse(rendered.contains("reasoning"));
    }

    @Test
    void historyDiagnosticsExposeFriendlyPageStateAndCountOnlyDebugCursors() {
        SettingsDiagnosticsAggregator.DiagnosticsInputs available = inputs();
        GuideSnapshot prior = available.guide().orElseThrow();
        GuideSessionSnapshot original = prior.sessions().getFirst();
        GuideHistoryCursor first = new GuideHistoryCursor(0, UUID.randomUUID());
        GuideHistoryCursor last = new GuideHistoryCursor(99, UUID.randomUUID());
        GuideHistoryCursor loadedFirst = new GuideHistoryCursor(20, UUID.randomUUID());
        GuideHistoryCursor loadedLast = new GuideHistoryCursor(40, UUID.randomUUID());
        GuideSessionSnapshot paged = new GuideSessionSnapshot(
                original.sessionId(), original.messages(), original.requests(),
                original.checkpoints(), original.modelSelection(),
                new GuideHistoryWindowSnapshot(
                        100, first, last, loadedFirst, loadedLast, true, true,
                        GuideHistoryPageState.LOADING, 7, null));
        GuideSnapshot guide = new GuideSnapshot(
                prior.actorId(), prior.selectedSession(), prior.modelMode(),
                prior.clientModelAvailable(), prior.serverModelAvailable(),
                prior.persistence(), List.of(paged), prior.updatedAt());
        SettingsDiagnosticsAggregator.DiagnosticsInputs pagedInputs =
                new SettingsDiagnosticsAggregator.DiagnosticsInputs(
                        available.settingsGeneration(), available.models(),
                        available.capabilities(), available.recipes(), Optional.of(guide),
                        available.historyActivity(), available.historyScopeKind(),
                        available.databaseSchema(), available.sources());

        SettingsDiagnosticsSnapshot normal =
                new SettingsDiagnosticsAggregator().snapshot(false, pagedInputs);
        SettingsDiagnosticCard history = normal.cards().stream()
                .filter(card -> card.domain() == Domain.HISTORY).findFirst().orElseThrow();
        assertEquals(FriendlyStatus.WORKING, history.friendlyStatus());
        assertTrue(history.noteKeys().contains(
                "screen.openallay.settings.diagnostics.history.on_demand"));
        assertTrue(history.noteKeys().contains(
                "screen.openallay.settings.diagnostics.history.page_loading"));

        SettingsDiagnosticsSnapshot.DebugHistory debug =
                new SettingsDiagnosticsAggregator().snapshot(true, pagedInputs)
                        .debug().orElseThrow().guide().orElseThrow().history();
        assertEquals(1, debug.loadedRequests());
        assertEquals(100, debug.totalRequests());
        assertEquals(20, debug.firstLoadedCount());
        assertEquals(40, debug.lastLoadedCount());
        assertFalse(debug.toString().contains(loadedFirst.requestId().toString()));
        assertFalse(debug.toString().contains(loadedLast.requestId().toString()));
    }

    @Test
    void absentRuntimeDomainsRemainExplicitlyNotConnected() {
        SettingsDiagnosticsAggregator.DiagnosticsInputs available = inputs();
        SettingsDiagnosticsSnapshot snapshot = new SettingsDiagnosticsAggregator().snapshot(
                true,
                new SettingsDiagnosticsAggregator.DiagnosticsInputs(
                        available.settingsGeneration(),
                        available.models(),
                        CapabilitySettingsView.defaults(),
                        RecipeSettingsView.defaults(),
                        Optional.empty(),
                        GuideHistoryActivity.idle(),
                        SettingsDiagnosticsAggregator.HistoryScopeKind.NONE,
                        GuideHistoryPartition.SCHEMA_VERSION,
                        List.of()));

        assertEquals(FriendlyStatus.NOT_CONNECTED, snapshot.cards().stream()
                .filter(card -> card.domain() == Domain.HISTORY)
                .findFirst().orElseThrow().friendlyStatus());
        assertEquals(FriendlyStatus.NOT_CONNECTED, snapshot.cards().stream()
                .filter(card -> card.domain() == Domain.CONTEXT)
                .findFirst().orElseThrow().friendlyStatus());
        assertTrue(snapshot.debug().orElseThrow().guide().isEmpty());
        assertTrue(snapshot.debug().orElseThrow().sources().isEmpty());
    }

    @Test
    void unavailableHistoryExposesOnlyStableFailureCode() {
        SettingsDiagnosticsAggregator.DiagnosticsInputs available = inputs();
        GuideSnapshot prior = available.guide().orElseThrow();
        GuideSnapshot unavailable = new GuideSnapshot(
                prior.actorId(),
                prior.selectedSession(),
                prior.modelMode(),
                prior.clientModelAvailable(),
                prior.serverModelAvailable(),
                new GuidePersistenceSnapshot(
                        GuidePersistenceSnapshot.State.UNAVAILABLE,
                        3,
                        2,
                        new GuideFailure(
                                "history_unavailable",
                                "authorization provider body secret-value")),
                prior.sessions(),
                prior.updatedAt());
        SettingsDiagnosticsSnapshot snapshot = new SettingsDiagnosticsAggregator().snapshot(
                true,
                new SettingsDiagnosticsAggregator.DiagnosticsInputs(
                        available.settingsGeneration(),
                        available.models(),
                        available.capabilities(),
                        available.recipes(),
                        Optional.of(unavailable),
                        GuideHistoryActivity.idle(),
                        available.historyScopeKind(),
                        available.databaseSchema(),
                        available.sources()));

        assertEquals(FriendlyStatus.UNAVAILABLE, snapshot.cards().stream()
                .filter(card -> card.domain() == Domain.HISTORY)
                .findFirst().orElseThrow().friendlyStatus());
        assertTrue(snapshot.debug().orElseThrow().failureCodes()
                .contains("history_unavailable"));
        assertFalse(snapshot.toString().contains("secret-value"));
        assertFalse(snapshot.toString().toLowerCase().contains("authorization"));
    }

    @Test
    void debugDataClassesRejectCredentialVocabularyAndEndpointPaths() {
        assertThrows(IllegalArgumentException.class, () ->
                new SettingsDiagnosticsSnapshot.DebugSource(
                        "viewer:rei",
                        "secret-value",
                        SettingsDiagnosticsAggregator.SourceState.AVAILABLE,
                        1,
                        null));
        assertThrows(IllegalArgumentException.class, () ->
                new SettingsDiagnosticsSnapshot.DebugModelProfile(
                        "primary",
                        ModelProtocol.OPENAI_CHAT,
                        "https://provider.example/v1/private",
                        "model-main",
                        true,
                        true,
                        true,
                        256_000,
                        false));
    }

    private static SettingsDiagnosticsAggregator.DiagnosticsInputs inputs() {
        return new SettingsDiagnosticsAggregator.DiagnosticsInputs(
                17,
                models(),
                capabilities(),
                RecipeSettingsView.defaults(),
                Optional.of(guide()),
                new GuideHistoryActivity(2, false),
                SettingsDiagnosticsAggregator.HistoryScopeKind.MULTIPLAYER_SERVER,
                GuideHistoryPartition.SCHEMA_VERSION,
                List.of(new SettingsDiagnosticsAggregator.SourceStatus(
                        "viewer:rei",
                        "generation-7",
                        SettingsDiagnosticsAggregator.SourceState.AVAILABLE,
                        42,
                        null)));
    }

    private static ModelProfileSettingsView models() {
        ModelProfileDefinition definition = new ModelProfileDefinition(
                "primary",
                "Primary",
                true,
                ModelProtocol.OPENAI_CHAT,
                URI.create("https://provider.example:8443/v1/private"),
                "model-main",
                "OPENALLAY_API_KEY",
                256_000,
                8_192,
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                null);
        ModelProfilesConfig config = new ModelProfilesConfig(
                ModelProfilesConfig.SCHEMA_VERSION, "primary", List.of(definition));
        return ModelProfileSettingsView.from(
                config,
                List.of(new ModelProfileSettingsView.Resolution(
                        definition, true, 256_000, null)),
                Set.of("OPENALLAY_API_KEY"),
                null,
                null);
    }

    private static CapabilitySettingsView capabilities() {
        CapabilitySettingsEntry tool = new CapabilitySettingsEntry(
                "openallay:core",
                "openallay:inspect_inventory",
                CapabilityKind.TOOL,
                "tool.inventory.title",
                "tool.inventory.description",
                null,
                true,
                true);
        return new CapabilitySettingsView(
                CapabilityPolicy.defaults(),
                new CapabilityCatalogSnapshot(List.of(tool)),
                Set.of(),
                Set.of());
    }

    private static GuideSnapshot guide() {
        GuideToolActivity tool = new GuideToolActivity(
                "call-secret",
                0,
                "openallay:inspect_inventory",
                GuideToolStatus.SUCCEEDED,
                JsonParser.parseString("""
                        {"authorization":"secret-value"}
                        """).getAsJsonObject(),
                List.of(GuideToolMessage.of(GuideToolMessage.Key.RESULT_COMPLETED)),
                List.of());
        GuideRequestSnapshot request = new GuideRequestSnapshot(
                REQUEST,
                "main",
                GuideTopology.CLIENT_LOCAL,
                "reasoning secret-value",
                List.of(new GuideTimelineEntry.Tool(0, tool)),
                GuideRequestStatus.RATE_LIMITED,
                List.of(),
                ModelUsage.empty(),
                2_000L,
                null,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                null);
        ContextCheckpoint checkpoint = new ContextCheckpoint(
                UUID.fromString("1590f528-55c7-4515-8a7d-57c5ae0f6c1b"),
                0,
                2,
                "a".repeat(64),
                "model-main",
                1,
                1,
                Instant.EPOCH,
                ContextCheckpoint.Status.SUCCEEDED,
                "reasoning secret-value",
                null,
                null,
                321);
        GuideSessionSnapshot session = new GuideSessionSnapshot(
                "main",
                List.of(new GuideMessage(
                        REQUEST,
                        GuideMessage.Role.ASSISTANT,
                        "authorization secret-value reasoning",
                        Instant.EPOCH)),
                List.of(request),
                List.of(checkpoint));
        return new GuideSnapshot(
                ACTOR,
                "main",
                GuideModelMode.CLIENT,
                true,
                false,
                new GuidePersistenceSnapshot(
                        GuidePersistenceSnapshot.State.SAVING, 3, 2, null),
                List.of(session),
                Instant.EPOCH.plusSeconds(1));
    }
}
