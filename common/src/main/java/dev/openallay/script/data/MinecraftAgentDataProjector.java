package dev.openallay.script.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.RegistryEntrySnapshot;
import dev.openallay.context.ToolInvocationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Projects only detached request records into the JavaScript data graph. */
public final class MinecraftAgentDataProjector {
    private final Gson gson;

    public MinecraftAgentDataProjector(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public Projection project(ToolInvocationContext context) {
        Objects.requireNonNull(context, "context");
        JsonObject root = new JsonObject();
        JsonArray capabilities = new JsonArray();
        List<EvidenceMetadata> evidence = new ArrayList<>();

        root.add("caller", gson.toJsonTree(context.caller()));
        root.add("metrics", gson.toJsonTree(context.metrics()));
        root.addProperty("capturedAt", context.capturedAt().toString());

        context.player().ifPresent(player -> {
            root.add("player", gson.toJsonTree(player));
            capabilities.add("player");
            evidence.add(player.evidence());
        });
        context.registries().ifPresent(registries -> {
            JsonObject registryRoot = gson.toJsonTree(registries).getAsJsonObject();
            root.add("registries", registryRoot);
            capabilities.add("registries");
            evidence.add(registries.evidence());
            addRegistryViews(root, registries.entries());
        });
        context.recipes().ifPresent(recipes -> {
            root.add("recipeCatalog", gson.toJsonTree(recipes));
            root.add("recipes", gson.toJsonTree(recipes.recipes()));
            capabilities.add("recipes");
            evidence.add(recipes.evidence());
        });
        context.observableGameState().ifPresent(game -> {
            root.add("game", gson.toJsonTree(game));
            capabilities.add("game");
            evidence.add(game.runtime().evidence());
            evidence.add(game.mods().evidence());
            evidence.add(game.options().evidence());
            evidence.add(game.packs().evidence());
            evidence.add(game.shaders().evidence());
            evidence.add(game.diagnostics().evidence());
            evidence.add(game.player().evidence());
            evidence.add(game.worldQueries().evidence());
        });

        root.add("capabilities", capabilities);
        root.add("extensions", new JsonObject());
        return new Projection(root, evidence.stream().distinct().toList());
    }

    private void addRegistryViews(JsonObject root, List<RegistryEntrySnapshot> entries) {
        java.util.Map<String, JsonArray> views = new java.util.TreeMap<>();
        for (RegistryEntrySnapshot entry : entries) {
            String name = pluralize(entry.kind().toLowerCase(Locale.ROOT));
            views.computeIfAbsent(name, ignored -> new JsonArray())
                    .add(gson.toJsonTree(entry));
        }
        views.forEach(root::add);
    }

    private static String pluralize(String kind) {
        return switch (kind) {
            case "item" -> "items";
            case "block" -> "blocks";
            case "fluid" -> "fluids";
            case "effect", "mob_effect" -> "effects";
            case "enchantment" -> "enchantments";
            case "entity", "entity_type" -> "entities";
            default -> kind.endsWith("s") ? kind : kind + "s";
        };
    }

    public record Projection(JsonObject data, List<EvidenceMetadata> evidence) {
        public Projection {
            data = Objects.requireNonNull(data, "data").deepCopy();
            evidence = List.copyOf(evidence);
        }
    }
}

