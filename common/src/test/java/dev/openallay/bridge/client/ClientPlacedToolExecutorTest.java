package dev.openallay.bridge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.openallay.agent.tool.LocalAgentToolExecutor;
import dev.openallay.bridge.protocol.BridgeProtocol;
import dev.openallay.bridge.protocol.CapabilityPayload;
import dev.openallay.bridge.protocol.RemoteToolCallPayload;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.ToolResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ClientPlacedToolExecutorTest {
    @Test
    void exposesOneLogicalDefinitionAndRoutesWorldQueryToTheServer() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("test", List.of(new ResourceReadTool()));
        RemoteCapabilityStore capabilities = new RemoteCapabilityStore();
        capabilities.replace(new CapabilityPayload(
                BridgeProtocol.VERSION,
                List.of(
                        capability("openallay:resource_read"),
                        capability("unique:fact")),
                false, 0, 0, 0, ""));
        AtomicReference<RemoteToolCallPayload> sent = new AtomicReference<>();
        RemoteToolExecutor remote = new RemoteToolExecutor(
                capabilities,
                new RemoteToolExecutor.Transport() {
                    @Override public void call(RemoteToolCallPayload payload) { sent.set(payload); }
                    @Override public void cancel(
                            dev.openallay.bridge.protocol.RemoteCancelPayload payload) {}
                });
        ClientPlacedToolExecutor tools = new ClientPlacedToolExecutor(
                new LocalAgentToolExecutor(registry, new Gson()), remote);

        assertEquals(2, tools.definitions().size());
        assertEquals(1, tools.definitions().stream()
                .filter(definition -> tools.canonicalToolId(definition.name()).orElseThrow()
                        .equals("openallay:resource_read"))
                .count());
        assertTrue(tools.definitions().stream().noneMatch(
                definition -> definition.name().startsWith("server__")));

        JsonObject options = paths("/game/options");
        var local = tools.execute(
                        "resource_read",
                        options,
                        ToolInvocationContext.developmentConsole("local"),
                        new CancellationSignal())
                .join();
        assertFalse(local.failure(), local.normalized().toString());
        assertEquals("client", local.normalized()
                .getAsJsonObject("value").get("placement").getAsString());
        assertEquals(null, sent.get());

        JsonObject query = paths("/world/dimension");
        tools.execute(
                "resource_read",
                query,
                ToolInvocationContext.developmentConsole("remote"),
                new CancellationSignal());
        assertEquals("openallay:resource_read", sent.get().toolId());
    }

    private static JsonObject paths(String... values) {
        JsonObject result = new JsonObject();
        com.google.gson.JsonArray paths = new com.google.gson.JsonArray();
        for (String value : values) paths.add(value);
        result.add("paths", paths);
        return result;
    }

    private static CapabilityPayload.RemoteToolCapability capability(String id) {
        return new CapabilityPayload.RemoteToolCapability(
                id, "Read " + id, "{\"type\":\"object\"}");
    }

    private static final class ResourceReadTool
            implements Tool<ResourceReadTool.Input, ResourceReadTool.Output> {
        record Input(List<String> paths) {}
        record Output(String placement) {}

        private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
                "openallay:resource_read",
                "Read resource paths",
                Input.class,
                Output.class,
                ToolAccess.READ_ONLY);

        @Override public ToolDescriptor<Input, Output> descriptor() { return DESCRIPTOR; }

        @Override
        public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
            return new ToolResult.Success<>(new Output("client"));
        }
    }
}
