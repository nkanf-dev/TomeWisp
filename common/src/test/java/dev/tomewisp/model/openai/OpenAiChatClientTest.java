package dev.tomewisp.model.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.model.ModelRequest;
import dev.tomewisp.model.ModelToolDefinition;
import dev.tomewisp.model.ModelTurn;
import dev.tomewisp.model.config.ModelConfig;
import dev.tomewisp.model.config.ModelProtocol;
import dev.tomewisp.model.config.SecretValue;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class OpenAiChatClientTest {
    @Test
    void parsesOpenAiToolCallAndUsesBearerHeader() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("Bearer test-secret", exchange.getRequestHeaders().getFirst("authorization"));
            byte[] body = """
                    {"id":"x","model":"compatible-model","choices":[{"finish_reason":"tool_calls",
                     "message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_x","type":"function",
                     "function":{"name":"tomewisp__test_fact","arguments":"{\\\"value\\\":42}"}}]}}],
                     "usage":{"prompt_tokens":4,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":1}}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
            ModelConfig config = new ModelConfig(
                    true,
                    ModelProtocol.OPENAI_CHAT,
                    uri,
                    "compatible-model",
                    SecretValue.of("test-secret"),
                    512,
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(10));
            JsonObject schema = JsonParser.parseString("{\"type\":\"object\"}").getAsJsonObject();
            ModelRequest request = new ModelRequest(
                    "Use tools.",
                    List.of(ModelMessage.userText("fact")),
                    List.of(new ModelToolDefinition("tomewisp__test_fact", "Get fact", schema)),
                    false);

            ModelTurn turn = new OpenAiChatClient(config, new Gson())
                    .complete(request, event -> {}, new CancellationSignal())
                    .join();
            ModelContent.ToolUse tool = turn.toolUses().getFirst();
            assertEquals("call_x", tool.id());
            assertEquals(42, tool.input().get("value").getAsInt());
            assertFalse(requestBody.get().contains("test-secret"));
            assertEquals(1, turn.usage().cacheReadTokens());
        } finally {
            server.stop(0);
        }
    }
}
