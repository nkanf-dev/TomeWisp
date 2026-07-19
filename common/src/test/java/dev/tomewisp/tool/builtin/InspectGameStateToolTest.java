package dev.tomewisp.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.context.CallerKind;
import dev.tomewisp.context.CallerSnapshot;
import dev.tomewisp.context.ContextMetrics;
import dev.tomewisp.context.DataAuthority;
import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.context.game.ObservableGameStateSnapshot;
import dev.tomewisp.agent.tool.ToolSchemaGenerator;
import dev.tomewisp.platform.InstalledModMetadata;
import dev.tomewisp.testing.GroundedTestFixtures;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.tool.gamestate.GameStateSection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class InspectGameStateToolTest {
    private final InspectGameStateTool tool = new InspectGameStateTool();

    @Test
    void exposesOneClosedSectionedReadOnlyTool() {
        assertEquals("tomewisp:inspect_game_state", tool.descriptor().id());
        assertEquals(java.util.Set.of(dev.tomewisp.context.ContextCapability.OBSERVABLE_GAME_STATE),
                tool.descriptor().requiredContext());
        assertEquals(
                GameStateSection.class,
                tool.descriptor().inputType().getRecordComponents()[0].getType());
        var schema = new ToolSchemaGenerator().generate(InspectGameStateTool.Input.class);
        assertTrue(schema.getAsJsonObject("properties").getAsJsonObject("section").has("enum"));
        assertFalse(schema.getAsJsonArray("required").asList().stream()
                .anyMatch(value -> value.getAsString().equals("query")));
        assertFalse(schema.get("additionalProperties").getAsBoolean());
    }

    @Test
    void listsModsThenReturnsExactMetadataWithEvidence() {
        InspectGameStateTool.Output list = success(new InspectGameStateTool.Input(
                GameStateSection.MODS, "list"));
        assertEquals(List.of("fabric-api", "tomewisp"),
                list.mods().installed().stream()
                        .map(InspectGameStateTool.ModSummary::id).toList());
        assertNull(list.mods().detail());
        assertEquals(2, list.mods().totalInstalled());
        assertEquals(DataCompleteness.COMPLETE, list.evidence().getFirst().completeness());

        InspectGameStateTool.Output detail = success(new InspectGameStateTool.Input(
                GameStateSection.MODS, "tomewisp"));
        assertTrue(detail.mods().installed().isEmpty());
        assertEquals("TomeWisp", detail.mods().detail().name());
        assertEquals("description", detail.mods().detail().description());
        assertNull(detail.options());
    }

    @Test
    void optionsAndDiagnosticsRequireRegisteredSelectors() {
        InspectGameStateTool.Output groups = success(new InspectGameStateTool.Input(
                GameStateSection.OPTIONS, "groups"));
        assertEquals(List.of("controls", "video"),
                groups.options().values().stream().map(ObservableGameStateSnapshot.OptionValue::group).toList());

        InspectGameStateTool.Output video = success(new InspectGameStateTool.Input(
                GameStateSection.OPTIONS, "group:video"));
        assertEquals("renderDistance", video.options().values().getFirst().key());

        InspectGameStateTool.Output position = success(new InspectGameStateTool.Input(
                GameStateSection.DIAGNOSTICS, "category:position"));
        assertTrue(position.diagnostics().values().stream()
                .anyMatch(value -> value.key().equals("coordinates")));
    }

    @Test
    void worldQueriesAreClosedAndNeverExecuteRawCommandText() {
        InspectGameStateTool.Output time = success(new InspectGameStateTool.Input(
                GameStateSection.WORLD_QUERY, "time"));
        assertEquals("1200", time.worldQuery().values().get("time").value());
        assertFalse(time.worldQuery().values().get("time").authoritative());

        assertFailure(GameStateSection.WORLD_QUERY, "/time query daytime");
        assertFailure(GameStateSection.WORLD_QUERY, "set_time");
        assertFailure(GameStateSection.WORLD_QUERY, "scan_nearby_blocks");
        assertFailure(GameStateSection.WORLD_QUERY, "container_contents");
        assertFailure(GameStateSection.OPTIONS, "java.lang.Runtime");
        assertFailure(GameStateSection.PLAYER, "nearby_chests");
    }

    @Test
    void playerQueriesExposeOnlyTheRequestedProjection() {
        InspectGameStateTool.Output summary = success(new InspectGameStateTool.Input(
                GameStateSection.PLAYER, "summary"));
        assertEquals("minecraft:overworld", summary.player().dimension());
        assertNull(summary.player().inventory());

        InspectGameStateTool.Output inventory = success(new InspectGameStateTool.Input(
                GameStateSection.PLAYER, "inventory"));
        assertTrue(inventory.player().inventory().complete());
        assertNull(inventory.player().position());
        assertNull(inventory.player().openScreen());

        InspectGameStateTool.Output ui = success(new InspectGameStateTool.Input(
                GameStateSection.PLAYER, "ui"));
        assertEquals("gameplay", ui.player().openScreen());
        assertNull(ui.player().displayName());
        assertNull(ui.player().inventory());
    }

    @Test
    void missingOptionalStateFailsWithoutInventingFacts() {
        ToolResult.Failure<?> failure = assertInstanceOf(
                ToolResult.Failure.class,
                tool.invoke(ToolInvocationContext.developmentConsole("test"),
                        new InspectGameStateTool.Input(GameStateSection.OVERVIEW, "summary")));
        assertEquals("observable_game_state_unavailable", failure.code());
    }

    @Test
    void consoleSnapshotKeepsWorldQueriesButDoesNotInventAPlayer() {
        ObservableGameStateSnapshot base = snapshot();
        ObservableGameStateSnapshot console = new ObservableGameStateSnapshot(
                base.capturedAt(), base.runtime(), base.mods(), base.options(), base.packs(),
                base.shaders(), base.diagnostics(),
                new ObservableGameStateSnapshot.PlayerUiState(
                        null, "unavailable", "", evidence(), List.of(
                                new ObservableGameStateSnapshot.SectionDiagnostic(
                                        "player_required", "No player is associated with this source"))),
                base.worldQueries());
        ToolInvocationContext context = new ToolInvocationContext(
                "console", Instant.EPOCH,
                new CallerSnapshot(CallerKind.CONSOLE, null, "console", true),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(console),
                new ContextMetrics(0, 0, 0, 0, 0));

        ToolResult.Success<InspectGameStateTool.Output> world = assertInstanceOf(
                ToolResult.Success.class,
                tool.invoke(context, new InspectGameStateTool.Input(
                        GameStateSection.WORLD_QUERY, "time")));
        assertEquals("1200", world.value().worldQuery().values().get("time").value());

        ToolResult.Failure<?> player = assertInstanceOf(
                ToolResult.Failure.class,
                tool.invoke(context, new InspectGameStateTool.Input(
                        GameStateSection.PLAYER, "summary")));
        assertEquals("invalid_tool_arguments", player.code());
    }

    @Test
    void permissionDiagnosticDeniesServerWorldQueriesBeforeLookup() {
        ObservableGameStateSnapshot base = snapshot();
        ObservableGameStateSnapshot denied = new ObservableGameStateSnapshot(
                base.capturedAt(), base.runtime(), base.mods(), base.options(), base.packs(),
                base.shaders(), base.diagnostics(), base.player(),
                new ObservableGameStateSnapshot.WorldQueriesState(
                        Map.of(), evidence(), List.of(
                                new ObservableGameStateSnapshot.SectionDiagnostic(
                                        "permission_required", "Game-master permission is required"))));
        var player = GroundedTestFixtures.player();
        ToolInvocationContext context = new ToolInvocationContext(
                "test", Instant.EPOCH,
                new CallerSnapshot(CallerKind.PLAYER, player.uuid(), player.displayName(), false),
                Optional.of(player), Optional.empty(), Optional.empty(), Optional.of(denied),
                new ContextMetrics(0, 0, 0, 0, 0));

        ToolResult.Failure<?> result = assertInstanceOf(
                ToolResult.Failure.class,
                tool.invoke(context, new InspectGameStateTool.Input(
                        GameStateSection.WORLD_QUERY, "time")));
        assertEquals("permission_denied", result.code());
    }

    @Test
    void snapshotDefensivelyCopiesEveryIncomingCollection() {
        ArrayList<InstalledModMetadata> mods = new ArrayList<>(snapshot().mods().installed());
        var state = new ObservableGameStateSnapshot.ModsState(mods, evidence(), List.of());
        mods.clear();
        assertEquals(2, state.installed().size());
    }

    private InspectGameStateTool.Output success(InspectGameStateTool.Input input) {
        ToolResult.Success<InspectGameStateTool.Output> success = assertInstanceOf(
                ToolResult.Success.class, tool.invoke(context(), input));
        return success.value();
    }

    private void assertFailure(GameStateSection section, String query) {
        ToolResult.Failure<?> failure = assertInstanceOf(
                ToolResult.Failure.class,
                tool.invoke(context(), new InspectGameStateTool.Input(section, query)));
        assertEquals("invalid_tool_arguments", failure.code());
    }

    private ToolInvocationContext context() {
        var player = GroundedTestFixtures.player();
        return new ToolInvocationContext(
                "test",
                Instant.EPOCH,
                new CallerSnapshot(CallerKind.PLAYER, player.uuid(), player.displayName(), false),
                Optional.of(player),
                Optional.empty(),
                Optional.empty(),
                Optional.of(snapshot()),
                new ContextMetrics(0, 0, player.inventory().slots().size(), 0, 0));
    }

    private ObservableGameStateSnapshot snapshot() {
        var player = GroundedTestFixtures.player();
        return new ObservableGameStateSnapshot(
                Instant.EPOCH,
                new ObservableGameStateSnapshot.RuntimeState(
                        "26.2", "Fabric", true, "singleplayer", evidence(), List.of()),
                new ObservableGameStateSnapshot.ModsState(List.of(
                        mod("fabric-api", "Fabric API"), mod("tomewisp", "TomeWisp")), evidence(), List.of()),
                new ObservableGameStateSnapshot.OptionsState(List.of(
                        new ObservableGameStateSnapshot.OptionValue(
                                "controls", "key.jump", "Jump", "Space"),
                        new ObservableGameStateSnapshot.OptionValue(
                                "video", "renderDistance", "Render Distance", "12")), evidence(), List.of()),
                new ObservableGameStateSnapshot.PacksState(List.of(
                        new ObservableGameStateSnapshot.PackInfo(
                                "vanilla", "Vanilla", "Built in", true, true, "compatible", "builtin")),
                        List.of(), evidence(), List.of()),
                new ObservableGameStateSnapshot.ShaderState(
                        false, "none", "", Map.of(), evidence(),
                        List.of(new ObservableGameStateSnapshot.SectionDiagnostic(
                                "shader_mod_not_loaded", "No shader provider is loaded"))),
                new ObservableGameStateSnapshot.DiagnosticsState(List.of(
                        new ObservableGameStateSnapshot.DiagnosticValue(
                                "position", "coordinates", "1 64 2"),
                        new ObservableGameStateSnapshot.DiagnosticValue(
                                "performance", "fps", "120")), evidence(), List.of()),
                new ObservableGameStateSnapshot.PlayerUiState(
                        player, "gameplay", "", evidence(), List.of()),
                new ObservableGameStateSnapshot.WorldQueriesState(Map.of(
                        "time", new ObservableGameStateSnapshot.QueryValue(
                                "time", "1200", false, "client_visible")), evidence(), List.of()));
    }

    private static InstalledModMetadata mod(String id, String name) {
        return new InstalledModMetadata(
                id, name, "1", "description", List.of("author"), List.of("MIT"),
                Map.of(), "client", List.of());
    }

    private static EvidenceMetadata evidence() {
        return new EvidenceMetadata(
                DataAuthority.CLIENT_VISIBLE,
                DataCompleteness.COMPLETE,
                Instant.EPOCH,
                "tomewisp:observable_game_state",
                "tomewisp:test_snapshot",
                "26.2",
                "fabric",
                Map.of());
    }
}
