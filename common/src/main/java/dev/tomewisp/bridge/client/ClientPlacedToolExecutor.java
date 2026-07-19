package dev.tomewisp.bridge.client;

import com.google.gson.JsonObject;
import dev.tomewisp.agent.tool.AgentToolExecutor;
import dev.tomewisp.agent.tool.AgentToolResult;
import dev.tomewisp.agent.tool.LocalAgentToolExecutor;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelToolDefinition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Exposes each logical Tool once and selects client/server placement in code. */
public final class ClientPlacedToolExecutor implements AgentToolExecutor {
    private static final String REMOTE_PREFIX = "server__";
    private final LocalAgentToolExecutor local;
    private final RemoteToolExecutor remote;

    public ClientPlacedToolExecutor(
            LocalAgentToolExecutor local, RemoteToolExecutor remote) {
        this.local = java.util.Objects.requireNonNull(local, "local");
        this.remote = java.util.Objects.requireNonNull(remote, "remote");
    }

    @Override
    public List<ModelToolDefinition> definitions() {
        List<ModelToolDefinition> result = new ArrayList<>();
        Set<String> canonicalIds = new LinkedHashSet<>();
        for (ModelToolDefinition definition : local.definitions()) {
            String canonical = local.canonicalToolId(definition.name()).orElseThrow();
            if (canonicalIds.add(canonical)) {
                result.add(definition);
            }
        }
        for (ModelToolDefinition definition : remote.definitions()) {
            String canonical = remote.canonicalToolId(definition.name()).orElseThrow();
            if (canonicalIds.add(canonical)) {
                result.add(new ModelToolDefinition(
                        withoutRemotePrefix(definition.name()),
                        definition.description(),
                        definition.inputSchema()));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public Set<ContextCapability> requiredContext() {
        return local.requiredContext();
    }

    @Override
    public Optional<String> canonicalToolId(String modelToolName) {
        if (modelToolName == null) {
            return Optional.empty();
        }
        Optional<String> localId = local.canonicalToolId(modelToolName);
        return localId.isPresent()
                ? localId
                : remote.canonicalToolId(REMOTE_PREFIX + modelToolName);
    }

    @Override
    public CompletableFuture<AgentToolResult> execute(
            String modelToolName,
            JsonObject arguments,
            ToolInvocationContext context,
            CancellationSignal cancellation) {
        Optional<String> localId = local.canonicalToolId(modelToolName);
        Optional<String> remoteId = modelToolName == null
                ? Optional.empty()
                : remote.canonicalToolId(REMOTE_PREFIX + modelToolName);
        if (localId.isPresent()) {
            if (remoteId.equals(localId) && useRemote(localId.orElseThrow(), arguments)) {
                return remote.execute(
                        REMOTE_PREFIX + modelToolName, arguments, context, cancellation);
            }
            return local.execute(modelToolName, arguments, context, cancellation);
        }
        if (remoteId.isPresent()) {
            return remote.execute(
                    REMOTE_PREFIX + modelToolName, arguments, context, cancellation);
        }
        JsonObject normalized = new JsonObject();
        normalized.addProperty("status", "failure");
        normalized.addProperty("code", "tool_unavailable");
        normalized.addProperty("message", "Tool is unavailable in this request");
        return CompletableFuture.completedFuture(
                new AgentToolResult(UNKNOWN_TOOL_ID, normalized, true));
    }

    private static boolean useRemote(String toolId, JsonObject arguments) {
        if (!toolId.equals("tomewisp:inspect_game_state") || arguments == null) {
            return false;
        }
        return arguments.has("section")
                && arguments.get("section").isJsonPrimitive()
                && arguments.get("section").getAsString().equalsIgnoreCase("WORLD_QUERY");
    }

    private static String withoutRemotePrefix(String name) {
        if (!name.startsWith(REMOTE_PREFIX)) {
            throw new IllegalArgumentException("Remote Tool definition lacks placement prefix");
        }
        return name.substring(REMOTE_PREFIX.length());
    }
}
