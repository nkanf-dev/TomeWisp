package dev.tomewisp.tool.builtin;

import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.BlockPositionSnapshot;
import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.InventorySnapshot;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.context.game.ObservableGameStateSnapshot;
import dev.tomewisp.agent.tool.ToolDescription;
import dev.tomewisp.agent.tool.ToolOptional;
import dev.tomewisp.context.game.ObservableGameStateSnapshot.DiagnosticValue;
import dev.tomewisp.context.game.ObservableGameStateSnapshot.ModsState;
import dev.tomewisp.context.game.ObservableGameStateSnapshot.OptionValue;
import dev.tomewisp.context.game.ObservableGameStateSnapshot.OptionsState;
import dev.tomewisp.context.game.ObservableGameStateSnapshot.PacksState;
import dev.tomewisp.platform.InstalledModMetadata;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.tool.gamestate.GameStateSection;
import dev.tomewisp.tool.gamestate.WorldQueryOperation;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** One strict, evidence-bearing entry point for directly player-observable game state. */
public final class InspectGameStateTool
        implements Tool<InspectGameStateTool.Input, InspectGameStateTool.Output> {
    @ToolDescription("Select one player-observable game-state section and its closed query form.")
    public record Input(
            @ToolDescription("Registered state section; recipes, guides, spatial scans and writes are separate.")
                    GameStateSection section,
            @ToolDescription("Optional section query documented by the inspect-game-state Skill; never command text.")
                    @ToolOptional String query) {}

    public record Output(
            GameStateSection section,
            String query,
            ObservableGameStateSnapshot.RuntimeState overview,
            ModsView mods,
            ObservableGameStateSnapshot.OptionsState options,
            ObservableGameStateSnapshot.PacksState packs,
            ObservableGameStateSnapshot.ShaderState shaders,
            ObservableGameStateSnapshot.DiagnosticsState diagnostics,
            PlayerView player,
            ObservableGameStateSnapshot.WorldQueriesState worldQuery,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            Objects.requireNonNull(section, "section");
            query = query == null ? "" : query;
            evidence = List.copyOf(evidence);
        }
    }

    /** Query-scoped player projection; omitted fields are not sent to the model. */
    public record PlayerView(
            String displayName,
            String dimension,
            BlockPositionSnapshot position,
            String gameMode,
            InventorySnapshot inventory,
            String openScreen,
            String openScreenTitle,
            List<ObservableGameStateSnapshot.SectionDiagnostic> diagnostics) {
        public PlayerView {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Lightweight list first; complete public loader metadata is returned only for one exact id. */
    public record ModsView(
            int totalInstalled,
            List<ModSummary> installed,
            InstalledModMetadata detail,
            List<ObservableGameStateSnapshot.SectionDiagnostic> diagnostics) {
        public ModsView {
            if (totalInstalled < 0) {
                throw new IllegalArgumentException("totalInstalled must be non-negative");
            }
            installed = List.copyOf(installed);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record ModSummary(String id, String name, String version, String environment) {}

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:inspect_game_state",
            "Inspect one registered section of directly player-observable game/client state. "
                    + "This read-only tool cannot execute commands or scan the surrounding world.",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY,
            Set.of(ContextCapability.OBSERVABLE_GAME_STATE));

    @Override
    public ToolDescriptor<Input, Output> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        if (input == null || input.section() == null) {
            return failure("invalid_tool_arguments", "section must be one registered game-state section");
        }
        ObservableGameStateSnapshot snapshot = context.observableGameState().orElse(null);
        if (snapshot == null) {
            return failure(
                    "observable_game_state_unavailable",
                    "player-observable game state was not captured for this request");
        }
        if (input.section() == GameStateSection.WORLD_QUERY
                && snapshot.worldQueries().diagnostics().stream()
                        .anyMatch(value -> value.code().equals("permission_required"))) {
            return failure(
                    "permission_denied",
                    "read-only server world queries require game-master command permission");
        }
        try {
            return new ToolResult.Success<>(select(snapshot, input.section(), normalize(input.query())));
        } catch (IllegalArgumentException invalid) {
            return failure("invalid_tool_arguments", invalid.getMessage());
        }
    }

    private static Output select(
            ObservableGameStateSnapshot snapshot, GameStateSection section, String query) {
        return switch (section) {
            case OVERVIEW -> {
                requireOneOf(query, "", "summary");
                yield output(section, query, snapshot.runtime(), null, null, null, null, null, null, null,
                        snapshot.runtime().evidence());
            }
            case MODS -> {
                ModsView selected = selectMods(snapshot.mods(), query);
                yield output(section, query, null, selected, null, null, null, null, null, null,
                        snapshot.mods().evidence());
            }
            case OPTIONS -> {
                OptionsState selected = selectOptions(snapshot.options(), query);
                yield output(section, query, null, null, selected, null, null, null, null, null,
                        selected.evidence());
            }
            case PACKS -> {
                PacksState selected = selectPacks(snapshot.packs(), query);
                yield output(section, query, null, null, null, selected, null, null, null, null,
                        selected.evidence());
            }
            case SHADERS -> {
                requireOneOf(query, "", "summary", "options");
                yield output(section, query, null, null, null, null, snapshot.shaders(), null, null, null,
                        snapshot.shaders().evidence());
            }
            case DIAGNOSTICS -> {
                var selected = selectDiagnostics(snapshot.diagnostics(), query);
                yield output(section, query, null, null, null, null, null, selected, null, null,
                        selected.evidence());
            }
            case PLAYER -> {
                requireOneOf(query, "", "summary", "inventory", "ui");
                yield output(section, query, null, null, null, null, null, null,
                        selectPlayer(snapshot.player(), query), null,
                        snapshot.player().evidence());
            }
            case WORLD_QUERY -> {
                WorldQueryOperation operation = WorldQueryOperation.parse(requireQuery(query));
                String id = operation.serializedName();
                var value = snapshot.worldQueries().values().get(id);
                if (value == null) {
                    throw new IllegalArgumentException("world query is unavailable in this captured topology");
                }
                var selected = new ObservableGameStateSnapshot.WorldQueriesState(
                        java.util.Map.of(id, value),
                        snapshot.worldQueries().evidence(),
                        snapshot.worldQueries().diagnostics());
                yield output(section, query, null, null, null, null, null, null, null, selected,
                        selected.evidence());
            }
        };
    }

    private static ModsView selectMods(ModsState state, String query) {
        if (query.isEmpty() || query.equals("list")) {
            List<ModSummary> summaries = state.installed().stream()
                    .map(mod -> new ModSummary(
                            mod.id(), mod.name(), mod.version(), mod.environment()))
                    .toList();
            return new ModsView(summaries.size(), summaries, null, state.diagnostics());
        }
        if (query.startsWith("/") || query.contains(" ") || query.contains(":")) {
            throw new IllegalArgumentException("mods query must be 'list' or an exact mod id");
        }
        InstalledModMetadata match = state.installed().stream()
                .filter(mod -> mod.id().equals(query))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown installed mod id"));
        return new ModsView(state.installed().size(), List.of(), match, state.diagnostics());
    }

    private static OptionsState selectOptions(OptionsState state, String query) {
        if (query.equals("groups")) {
            List<OptionValue> groups = state.values().stream()
                    .map(OptionValue::group)
                    .distinct()
                    .sorted()
                    .map(group -> new OptionValue(group, "group:" + group, group, "available"))
                    .toList();
            return new OptionsState(groups, state.evidence(), state.diagnostics());
        }
        if (query.startsWith("group:")) {
            String group = exactSuffix(query, "group:");
            return subsetOptions(state, state.values().stream()
                    .filter(value -> value.group().equals(group)).toList());
        }
        if (query.startsWith("key:")) {
            String key = exactSuffix(query, "key:");
            return subsetOptions(state, state.values().stream()
                    .filter(value -> value.key().equalsIgnoreCase(key)).toList());
        }
        throw new IllegalArgumentException("options query must be groups, group:<id>, or key:<id>");
    }

    private static OptionsState subsetOptions(OptionsState state, List<OptionValue> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("unknown option group or key");
        }
        return new OptionsState(values, state.evidence(), state.diagnostics());
    }

    private static PacksState selectPacks(PacksState state, String query) {
        requireOneOf(query, "resource", "data", "summary");
        if (query.equals("resource")) {
            return new PacksState(state.resourcePacks(), List.of(), state.evidence(), state.diagnostics());
        }
        if (query.equals("data")) {
            return new PacksState(List.of(), state.visibleDataPacks(), state.evidence(), state.diagnostics());
        }
        return state;
    }

    private static ObservableGameStateSnapshot.DiagnosticsState selectDiagnostics(
            ObservableGameStateSnapshot.DiagnosticsState state, String query) {
        if (query.isEmpty() || query.equals("categories")) {
            List<DiagnosticValue> categories = state.values().stream()
                    .map(DiagnosticValue::category)
                    .distinct()
                    .sorted()
                    .map(category -> new DiagnosticValue(category, "category:" + category, "available"))
                    .toList();
            return new ObservableGameStateSnapshot.DiagnosticsState(
                    categories, state.evidence(), state.diagnostics());
        }
        if (!query.startsWith("category:")) {
            throw new IllegalArgumentException("diagnostics query must be categories or category:<id>");
        }
        String category = exactSuffix(query, "category:");
        List<DiagnosticValue> matches = state.values().stream()
                .filter(value -> value.category().equals(category))
                .sorted(Comparator.comparing(DiagnosticValue::key))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("unknown diagnostics category");
        }
        return new ObservableGameStateSnapshot.DiagnosticsState(
                matches, state.evidence(), state.diagnostics());
    }

    private static PlayerView selectPlayer(
            ObservableGameStateSnapshot.PlayerUiState state, String query) {
        var player = state.player();
        if (player == null) {
            throw new IllegalArgumentException("player state is unavailable in this captured topology");
        }
        if (query.equals("inventory")) {
            return new PlayerView(
                    player.displayName(), null, null, null, player.inventory(),
                    null, null, state.diagnostics());
        }
        if (query.equals("ui")) {
            return new PlayerView(
                    null, null, null, null, null,
                    state.openScreen(), state.openScreenTitle(), state.diagnostics());
        }
        return new PlayerView(
                player.displayName(), player.dimension(), player.position(), player.gameMode(), null,
                state.openScreen(), state.openScreenTitle(), state.diagnostics());
    }

    private static Output output(
            GameStateSection section,
            String query,
            ObservableGameStateSnapshot.RuntimeState overview,
            ModsView mods,
            ObservableGameStateSnapshot.OptionsState options,
            ObservableGameStateSnapshot.PacksState packs,
            ObservableGameStateSnapshot.ShaderState shaders,
            ObservableGameStateSnapshot.DiagnosticsState diagnostics,
            PlayerView player,
            ObservableGameStateSnapshot.WorldQueriesState worldQuery,
            EvidenceMetadata evidence) {
        return new Output(
                section, query, overview, mods, options, packs, shaders, diagnostics, player,
                worldQuery, List.of(evidence));
    }

    private static String normalize(String query) {
        return query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    }

    private static String requireQuery(String query) {
        if (query.isEmpty()) {
            throw new IllegalArgumentException("this section requires an exact query operation");
        }
        return query;
    }

    private static String exactSuffix(String query, String prefix) {
        String suffix = query.substring(prefix.length());
        if (suffix.isBlank() || suffix.contains("/") || suffix.contains(" ") || suffix.contains(":")) {
            throw new IllegalArgumentException("query selector is malformed");
        }
        return suffix;
    }

    private static void requireOneOf(String actual, String... allowed) {
        for (String value : allowed) {
            if (actual.equals(value)) {
                return;
            }
        }
        throw new IllegalArgumentException("unsupported query for selected section");
    }

    private static <T> ToolResult.Failure<T> failure(String code, String message) {
        return new ToolResult.Failure<>(code, message);
    }
}
