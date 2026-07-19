package dev.openallay.agent.tool.result;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.openallay.agent.tool.AgentToolExecutor;
import dev.openallay.agent.tool.AgentToolResult;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelToolDefinition;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Converts internal Tool JSON into compact model-facing text with progressive retrieval. */
public final class ProgressiveToolResultExecutor implements AgentToolExecutor {
    public static final String TOOL_ID = "openallay:read_tool_result";
    public static final String MODEL_NAME = "openallay_read_tool_result";
    public static final int PROJECTION_BYTES = 8 * 1024;
    public static final int UI_PROJECTION_BYTES = 16 * 1024;
    private static final int PAGE_BYTES = 7 * 1024;
    private final AgentToolExecutor delegate;
    private final ToolResultResourceStore store;
    private final List<ModelToolDefinition> definitions;

    public ProgressiveToolResultExecutor(AgentToolExecutor delegate) {
        this(delegate, new ToolResultResourceStore());
    }

    ProgressiveToolResultExecutor(AgentToolExecutor delegate, ToolResultResourceStore store) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        this.store = java.util.Objects.requireNonNull(store, "store");
        List<ModelToolDefinition> combined = new ArrayList<>(delegate.definitions());
        combined.add(new ModelToolDefinition(MODEL_NAME,
                "Read a previously shortened Tool result. Use describe first, then read or search; never request the whole result at once.",
                schema()));
        definitions = List.copyOf(combined);
    }

    @Override public List<ModelToolDefinition> definitions() { return definitions; }
    @Override public Set<ContextCapability> requiredContext() { return delegate.requiredContext(); }

    @Override
    public Optional<String> canonicalToolId(String modelToolName) {
        if (TOOL_ID.equals(modelToolName) || MODEL_NAME.equals(modelToolName)) {
            return Optional.of(TOOL_ID);
        }
        return delegate.canonicalToolId(modelToolName);
    }

    @Override
    public CompletableFuture<AgentToolResult> execute(
            String modelToolName,
            JsonObject arguments,
            ToolInvocationContext context,
            CancellationSignal cancellation) {
        cancellation.throwIfCancelled();
        ToolResultResourceStore.Owner owner = owner(context);
        if (TOOL_ID.equals(modelToolName) || MODEL_NAME.equals(modelToolName)) {
            return CompletableFuture.completedFuture(read(owner, arguments));
        }
        return delegate.execute(modelToolName, arguments, context, cancellation)
                .thenApply(result -> project(owner, result));
    }

    public void release(ToolInvocationContext context) {
        store.release(owner(context));
    }

    private AgentToolResult project(
            ToolResultResourceStore.Owner owner, AgentToolResult result) {
        JsonObject exact = result.normalized();
        List<String> lines = flattenForModel(exact);
        String content = joinWithin(lines, PAGE_BYTES);
        boolean shortened = exact.toString().getBytes(StandardCharsets.UTF_8).length
                > PROJECTION_BYTES || lines.size() > content.lines().count();
        String ref = shortened ? store.retain(owner, exact) : null;
        if (shortened) {
            content += "\nmore = true\nresult_ref = " + ref
                    + "\nnext = Call openallay_read_tool_result with action=describe, read, or search.";
        }
        JsonObject compact = new JsonObject();
        compact.addProperty("status", result.failure() ? "failure" : "success");
        compact.addProperty("content", fitJsonString(content, PROJECTION_BYTES - 128));
        if (ref != null) {
            compact.addProperty("resultRef", ref);
            compact.addProperty("more", true);
        }
        JsonObject ui = ref == null ? exact : uiProjection(exact, ref);
        return new AgentToolResult(result.toolId(), ui, compact, result.failure());
    }

    private AgentToolResult read(ToolResultResourceStore.Owner owner, JsonObject input) {
        String ref = string(input, "resultRef");
        String action = string(input, "action").toLowerCase(java.util.Locale.ROOT);
        if (action.isBlank()) action = "read";
        int offset = integer(input, "offset", 0);
        String query = string(input, "query");
        Optional<ToolResultResourceStore.Page> page = switch (action) {
            case "describe" -> store.read(owner, ref, 0, null, 1024);
            case "read" -> store.read(owner, ref, offset, null, PAGE_BYTES);
            case "search" -> query.isBlank()
                    ? Optional.empty()
                    : store.read(owner, ref, offset, query, PAGE_BYTES);
            default -> Optional.empty();
        };
        if (page.isEmpty()) {
            return failure("resource_not_found",
                    "The result reference is invalid, expired, or unavailable to this request");
        }
        ToolResultResourceStore.Page value = page.orElseThrow();
        StringBuilder content = new StringBuilder();
        content.append("result_ref = ").append(ref).append('\n')
                .append("total_bytes = ").append(value.resource().totalBytes()).append('\n')
                .append("matched_lines = ").append(value.matchCount()).append('\n');
        value.lines().forEach(line -> content.append(line).append('\n'));
        if (value.nextOffset() != null) {
            content.append("more = true\nnext_offset = ").append(value.nextOffset()).append('\n');
        } else {
            content.append("more = false\n");
        }
        JsonObject normalized = new JsonObject();
        normalized.addProperty("status", "success");
        normalized.addProperty("content", fitJsonString(content.toString(), PROJECTION_BYTES - 128));
        normalized.addProperty("resultRef", ref);
        normalized.addProperty("more", value.nextOffset() != null);
        return new AgentToolResult(TOOL_ID, normalized, normalized, false);
    }

    private static AgentToolResult failure(String code, String message) {
        JsonObject normalized = new JsonObject();
        normalized.addProperty("status", "failure");
        normalized.addProperty("code", code);
        normalized.addProperty("message", message);
        JsonObject modelPayload = new JsonObject();
        modelPayload.addProperty("content",
                "status = failure\ncode = " + code + "\nmessage = " + message + "\n");
        return new AgentToolResult(TOOL_ID, normalized, modelPayload, true);
    }

    private static List<String> flattenForModel(JsonObject exact) {
        ToolResultResourceStore temporary = new ToolResultResourceStore();
        ToolResultResourceStore.Owner owner = new ToolResultResourceStore.Owner("projection", "projection");
        String ref = temporary.retain(owner, exact);
        return temporary.read(owner, ref, 0, null, Integer.MAX_VALUE - 8)
                .orElseThrow().lines();
    }

    private static JsonObject uiProjection(JsonObject exact, String ref) {
        JsonObject projected = pruneObject(exact, 4, 1024);
        JsonObject resource = new JsonObject();
        resource.addProperty("resultRef", ref);
        resource.addProperty("complete", false);
        projected.add("_openallayResult", resource);
        if (projected.toString().getBytes(StandardCharsets.UTF_8).length
                <= UI_PROJECTION_BYTES) {
            return projected;
        }
        JsonObject minimal = new JsonObject();
        minimal.addProperty("status", exact.has("status")
                ? exact.get("status").getAsString() : "success");
        JsonObject value = new JsonObject();
        value.addProperty("summary", "The Tool result is too large for one detail page");
        value.addProperty("resultRef", ref);
        value.addProperty("complete", false);
        minimal.add("value", value);
        minimal.add("_openallayResult", resource);
        return minimal;
    }

    private static JsonObject pruneObject(JsonObject source, int arrayItems, int stringBytes) {
        JsonObject result = new JsonObject();
        for (var entry : source.entrySet()) {
            result.add(entry.getKey(), prune(entry.getValue(), arrayItems, stringBytes));
        }
        return result;
    }

    private static com.google.gson.JsonElement prune(
            com.google.gson.JsonElement source, int arrayItems, int stringBytes) {
        if (source == null || source.isJsonNull()) return com.google.gson.JsonNull.INSTANCE;
        if (source.isJsonPrimitive()) {
            if (source.getAsJsonPrimitive().isString()) {
                return new com.google.gson.JsonPrimitive(
                        ToolResultResourceStore.utf8Prefix(source.getAsString(), stringBytes));
            }
            return source.deepCopy();
        }
        if (source.isJsonArray()) {
            JsonArray result = new JsonArray();
            int count = Math.min(arrayItems, source.getAsJsonArray().size());
            for (int index = 0; index < count; index++) {
                result.add(prune(source.getAsJsonArray().get(index), arrayItems, stringBytes));
            }
            return result;
        }
        return pruneObject(source.getAsJsonObject(), arrayItems, stringBytes);
    }

    private static String joinWithin(List<String> lines, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (String line : lines) {
            int next = line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes + next > maxBytes) break;
            result.append(line).append('\n');
            bytes += next;
        }
        return result.toString();
    }

    private static String fitJsonString(String value, int maxBytes) {
        return ToolResultResourceStore.utf8Prefix(value, Math.max(0, maxBytes));
    }

    private static ToolResultResourceStore.Owner owner(ToolInvocationContext context) {
        String actor = context.caller().uuid() == null
                ? context.caller().kind().name().toLowerCase(java.util.Locale.ROOT)
                : context.caller().uuid().toString();
        return new ToolResultResourceStore.Owner(actor, context.correlationId());
    }

    private static String string(JsonObject input, String field) {
        return input != null && input.has(field) && input.get(field).isJsonPrimitive()
                ? input.get(field).getAsString() : "";
    }

    private static int integer(JsonObject input, String field, int fallback) {
        try {
            return input != null && input.has(field) ? input.get(field).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        properties.add("resultRef", stringSchema("Opaque result_ref from a shortened Tool result"));
        JsonObject action = stringSchema("describe, read, or search");
        JsonArray values = new JsonArray();
        values.add("describe"); values.add("read"); values.add("search");
        action.add("enum", values);
        properties.add("action", action);
        JsonObject offset = new JsonObject();
        offset.addProperty("type", "integer"); offset.addProperty("minimum", 0);
        properties.add("offset", offset);
        properties.add("query", stringSchema("Literal text used only with search"));
        schema.add("properties", properties);
        JsonArray required = new JsonArray(); required.add("resultRef"); required.add("action");
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject stringSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("description", description);
        return schema;
    }
}
