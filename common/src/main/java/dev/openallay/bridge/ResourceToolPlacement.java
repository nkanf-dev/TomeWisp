package dev.openallay.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Deterministic placement for the shared VFS Tool family. */
public final class ResourceToolPlacement {
    public enum Side { CLIENT, SERVER }
    public enum Decision { LOCAL, REMOTE, CONFLICT }

    private ResourceToolPlacement() {}

    public static boolean isResourceTool(String toolId) {
        return toolId != null && switch (toolId) {
            case "openallay:resource_list",
                    "openallay:resource_read",
                    "openallay:resource_glob",
                    "openallay:resource_grep",
                    "openallay:resource_query" -> true;
            default -> false;
        };
    }

    public static Decision decide(String toolId, JsonObject arguments, Side agentSide) {
        if (!isResourceTool(toolId)) return Decision.LOCAL;
        List<String> paths = new ArrayList<>();
        collectPaths(arguments, paths);
        boolean client = paths.stream().anyMatch(ResourceToolPlacement::clientOwned);
        boolean server = paths.stream().anyMatch(ResourceToolPlacement::serverOwned);
        if (client && server) return Decision.CONFLICT;
        if (!client && !server) return Decision.LOCAL;
        Side owner = client ? Side.CLIENT : Side.SERVER;
        return owner == agentSide ? Decision.LOCAL : Decision.REMOTE;
    }

    private static void collectPaths(JsonElement value, List<String> result) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isString()) {
                String text = value.getAsString();
                if (text.startsWith("/")) result.add(text);
            }
            return;
        }
        if (value instanceof JsonArray array) {
            array.forEach(child -> collectPaths(child, result));
            return;
        }
        value.getAsJsonObject().entrySet().stream()
                .filter(entry -> !entry.getKey().equals("cursor"))
                .forEach(entry -> collectPaths(entry.getValue(), result));
    }

    private static boolean clientOwned(String path) {
        return under(path, "/game") || under(path, "/player");
    }

    private static boolean serverOwned(String path) {
        return under(path, "/world");
    }

    private static boolean under(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }
}
