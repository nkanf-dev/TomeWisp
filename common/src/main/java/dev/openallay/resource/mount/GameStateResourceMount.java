package dev.openallay.resource.mount;

import dev.openallay.context.game.ObservableGameStateSnapshot;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.resource.vfs.ResourceValues;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class GameStateResourceMount implements ResourceMount {
    private final Supplier<ObservableGameStateSnapshot> source;
    private long generation;

    public GameStateResourceMount(Supplier<ObservableGameStateSnapshot> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public ResourcePath root() {
        return ResourcePath.of("game");
    }

    @Override
    public synchronized ResourceSnapshot snapshot() {
        ObservableGameStateSnapshot state = Objects.requireNonNull(source.get(), "game state snapshot");
        ResourceTreeBuilder tree = new ResourceTreeBuilder(root(), state.runtime().evidence());
        tree.put(path("runtime"), ResourceKind.RECORD, ResourceValues.record(Map.of(
                "game_version", state.runtime().gameVersion(),
                "loader", state.runtime().loader(),
                "development", state.runtime().developmentEnvironment(),
                "connection", state.runtime().connectionKind())),
                List.of(), state.runtime().evidence(), ResourcePresentation.none());

        for (ObservableGameStateSnapshot.OptionValue option : state.options().values()) {
            tree.put(path("options", option.group(), option.key()), ResourceKind.RECORD,
                    ResourceValues.record(Map.of(
                            "group", option.group(), "key", option.key(),
                            "name", option.displayName(), "value", option.value())),
                    List.of(), state.options().evidence(),
                    new ResourcePresentation(ResourcePresentation.Kind.OPTIONS, Map.of("option", option.key())));
        }

        int packIndex = 0;
        for (ObservableGameStateSnapshot.PackInfo pack : state.packs().resourcePacks()) {
            tree.put(path("packs", "resource", Integer.toString(packIndex++)), ResourceKind.RECORD,
                    ResourceValues.record(Map.of(
                            "id", pack.id(), "title", pack.title(), "description", pack.description(),
                            "selected", pack.selected(), "required", pack.required(),
                            "compatibility", pack.compatibility(), "source", pack.source())),
                    List.of(), state.packs().evidence(), ResourcePresentation.none());
        }
        tree.put(path("packs", "data", "visible"), ResourceKind.LIST,
                ResourceValues.strings(state.packs().visibleDataPacks()), List.of(), state.packs().evidence(),
                ResourcePresentation.none());

        LinkedHashMap<String, ResourceValue> shader = new LinkedHashMap<>();
        shader.put("integration_available", new ResourceValue.Scalar(state.shaders().integrationAvailable()));
        shader.put("provider", new ResourceValue.Scalar(state.shaders().provider()));
        shader.put("selected_pack", new ResourceValue.Scalar(state.shaders().selectedPack()));
        LinkedHashMap<String, ResourceValue> shaderOptions = new LinkedHashMap<>();
        state.shaders().publicOptions().forEach((key, value) -> shaderOptions.put(key, new ResourceValue.Scalar(value)));
        shader.put("options", new ResourceValue.RecordValue(shaderOptions));
        tree.put(path("shaders"), ResourceKind.RECORD, new ResourceValue.RecordValue(shader), List.of(),
                state.shaders().evidence(), ResourcePresentation.none());

        for (ObservableGameStateSnapshot.DiagnosticValue diagnostic : state.diagnostics().values()) {
            tree.put(path("diagnostics", diagnostic.category(), diagnostic.key()), ResourceKind.SCALAR,
                    new ResourceValue.Scalar(diagnostic.value()), List.of(), state.diagnostics().evidence(),
                    new ResourcePresentation(ResourcePresentation.Kind.DIAGNOSTICS, Map.of()));
        }
        for (Map.Entry<String, ObservableGameStateSnapshot.QueryValue> entry : state.worldQueries().values().entrySet()) {
            ObservableGameStateSnapshot.QueryValue value = entry.getValue();
            tree.put(path("queries", entry.getKey()), ResourceKind.RECORD,
                    ResourceValues.record(Map.of(
                            "operation", value.operation(), "value", value.value(),
                            "authoritative", value.authoritative(), "authority_note", value.authorityNote())),
                    List.of(), state.worldQueries().evidence(), ResourcePresentation.none());
        }
        return new ResourceSnapshot(root(), "game-" + ++generation, state.capturedAt(), tree.build());
    }

    private static ResourcePath path(String... children) {
        ResourcePath path = ResourcePath.of("game");
        for (String child : children) {
            path = path.child(child);
        }
        return path;
    }
}
