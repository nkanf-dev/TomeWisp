package dev.tomewisp.bridge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.tomewisp.agent.tool.LocalAgentToolExecutor;
import dev.tomewisp.bridge.protocol.BridgeProtocol;
import dev.tomewisp.bridge.protocol.CapabilityPayload;
import dev.tomewisp.bridge.protocol.RemoteToolCallPayload;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ClientPlacedToolExecutorTest {
    @Test
    void exposesOneLogicalDefinitionAndRoutesWorldQueryToTheServer() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("test", List.of(new InspectTool()));
        RemoteCapabilityStore capabilities = new RemoteCapabilityStore();
        capabilities.replace(new CapabilityPayload(
                BridgeProtocol.VERSION,
                List.of(
                        capability("tomewisp:inspect_game_state"),
                        capability("unique:fact")),
                false, 0, 0, 0, ""));
        AtomicReference<RemoteToolCallPayload> sent = new AtomicReference<>();
        RemoteToolExecutor remote = new RemoteToolExecutor(
                capabilities,
                new RemoteToolExecutor.Transport() {
                    @Override public void call(RemoteToolCallPayload payload) { sent.set(payload); }
                    @Override public void cancel(
                            dev.tomewisp.bridge.protocol.RemoteCancelPayload payload) {}
                });
        ClientPlacedToolExecutor tools = new ClientPlacedToolExecutor(
                new LocalAgentToolExecutor(registry, new Gson()), remote);

        assertEquals(2, tools.definitions().size());
        assertEquals(1, tools.definitions().stream()
                .filter(definition -> tools.canonicalToolId(definition.name()).orElseThrow()
                        .equals("tomewisp:inspect_game_state"))
                .count());
        assertTrue(tools.definitions().stream().noneMatch(
                definition -> definition.name().startsWith("server__")));

        JsonObject options = new JsonObject();
        options.addProperty("section", "OPTIONS");
        var local = tools.execute(
                        "tomewisp__inspect_game_state",
                        options,
                        ToolInvocationContext.developmentConsole("local"),
                        new CancellationSignal())
                .join();
        assertFalse(local.failure());
        assertEquals("client", local.normalized()
                .getAsJsonObject("value").get("placement").getAsString());
        assertEquals(null, sent.get());

        JsonObject query = new JsonObject();
        query.addProperty("section", "WORLD_QUERY");
        tools.execute(
                "tomewisp__inspect_game_state",
                query,
                ToolInvocationContext.developmentConsole("remote"),
                new CancellationSignal());
        assertEquals("tomewisp:inspect_game_state", sent.get().toolId());
    }

    private static CapabilityPayload.RemoteToolCapability capability(String id) {
        return new CapabilityPayload.RemoteToolCapability(
                id, "Read " + id, "{\"type\":\"object\"}");
    }

    private static final class InspectTool
            implements Tool<InspectTool.Input, InspectTool.Output> {
        record Input(String section) {}
        record Output(String placement) {}

        private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
                "tomewisp:inspect_game_state",
                "Inspect game state",
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
