package dev.tomewisp.model.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.model.ModelRequest;
import dev.tomewisp.model.ModelRole;
import dev.tomewisp.model.ModelToolDefinition;
import dev.tomewisp.model.ModelTurn;
import dev.tomewisp.model.config.ModelConfig;
import dev.tomewisp.model.config.ModelProtocol;
import dev.tomewisp.model.config.SecretValue;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class AnthropicMessagesClientTest {
    @Test
    void performsToolUseAndToolResultContinuationWithoutLeakingKey() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> secondBody = new AtomicReference<>();
        try (Server server = new Server(exchange -> {
            int call = calls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("test-secret", exchange.getRequestHeaders().getFirst("x-api-key"));
            assertFalse(body.contains("test-secret"));
            if (call == 1) {
                respond(exchange, "application/json", """
                        {"id":"m1","model":"mimo-v2.5-pro","stop_reason":"tool_use",
                         "content":[{"type":"thinking","thinking":"need fact","signature":""},
                         {"type":"tool_use","id":"call_1","name":"tomewisp__test_fact","input":{"value":42}}],
                         "usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":2}}
                        """);
            } else {
                secondBody.set(body);
                respond(exchange, "application/json", """
                        {"id":"m2","model":"mimo-v2.5-pro","stop_reason":"end_turn",
                         "content":[{"type":"text","text":"事实是 42。"}],
                         "usage":{"input_tokens":12,"output_tokens":4,"cache_read_input_tokens":8}}
                        """);
            }
        })) {
            AnthropicMessagesClient client = new AnthropicMessagesClient(config(server.uri()), new Gson());
            List<ModelEvent> events = new ArrayList<>();
            ModelRequest initial = request(List.of(ModelMessage.userText("查询事实")), false);
            ModelTurn first = client.complete(initial, events::add, new CancellationSignal()).join();
            ModelContent.ToolUse tool = first.toolUses().getFirst();
            assertEquals("tomewisp__test_fact", tool.name());
            assertEquals(42, tool.input().get("value").getAsInt());

            ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT, first.content());
            ModelMessage result = new ModelMessage(
                    ModelRole.USER,
                    List.of(new ModelContent.ToolResult(
                            tool.id(), JsonParser.parseString("{\"fact\":42}"), false)));
            ModelTurn second = client.complete(
                            request(List.of(ModelMessage.userText("查询事实"), assistant, result), false),
                            events::add,
                            new CancellationSignal())
                    .join();

            assertEquals("事实是 42。", second.text());
            JsonObject encoded = JsonParser.parseString(secondBody.get()).getAsJsonObject();
            assertTrue(encoded.toString().contains("tool_result"));
            assertTrue(encoded.toString().contains("call_1"));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void streamsUtf8TextAndToolInputAcrossEvents() throws Exception {
        try (Server server = new Server(exchange -> {
            exchange.getResponseHeaders().add("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String stream = """
                    event: message_start
                    data: {"type":"message_start","message":{"model":"mimo-v2.5-pro","usage":{"input_tokens":3,"cache_read_input_tokens":1}}}

                    event: content_block_start
                    data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"铁锭"}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":0}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """;
            byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
            for (byte value : bytes) {
                exchange.getResponseBody().write(value);
            }
            exchange.close();
        })) {
            List<ModelEvent> events = new ArrayList<>();
            ModelTurn turn = new AnthropicMessagesClient(config(server.uri()), new Gson())
                    .complete(request(List.of(ModelMessage.userText("test")), true), events::add, new CancellationSignal())
                    .join();
            assertEquals("铁锭", turn.text());
            assertTrue(events.stream().anyMatch(event -> event instanceof ModelEvent.TextDelta delta
                    && delta.text().equals("铁锭")));
            assertEquals(2, turn.usage().outputTokens());
        }
    }

    private static ModelRequest request(List<ModelMessage> messages, boolean stream) {
        JsonObject schema = JsonParser.parseString("""
                {"type":"object","properties":{"value":{"type":"integer"}},"required":["value"]}
                """).getAsJsonObject();
        return new ModelRequest(
                "Use tools for facts.",
                messages,
                List.of(new ModelToolDefinition("tomewisp__test_fact", "Get a fact", schema)),
                stream);
    }

    private static ModelConfig config(URI uri) {
        return new ModelConfig(
                true,
                ModelProtocol.ANTHROPIC_MESSAGES,
                uri.resolve("v1/"),
                "mimo-v2.5-pro",
                SecretValue.of("test-secret"),
                1024,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10));
    }

    private static void respond(HttpExchange exchange, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class Server implements AutoCloseable {
        private final HttpServer server;

        private Server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/messages", handler);
            server.start();
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
