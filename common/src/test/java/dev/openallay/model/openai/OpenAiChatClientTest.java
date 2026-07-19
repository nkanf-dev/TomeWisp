package dev.openallay.model.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelClientException;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelEvent;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRequest;
import dev.openallay.model.ModelToolDefinition;
import dev.openallay.model.ModelTurn;
import dev.openallay.model.config.ModelConfig;
import dev.openallay.model.config.ModelProtocol;
import dev.openallay.model.config.SecretValue;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
                     "function":{"name":"openallay__test_fact","arguments":"{\\\"value\\\":42}"}}]}}],
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
                    128_000,
                    512,
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(10));
            JsonObject schema = JsonParser.parseString("{\"type\":\"object\"}").getAsJsonObject();
            ModelRequest request = new ModelRequest(
                    "Use tools.",
                    List.of(ModelMessage.userText("fact")),
                    List.of(new ModelToolDefinition("openallay__test_fact", "Get fact", schema)),
                    false);

            List<ModelEvent> events = new ArrayList<>();
            ModelTurn turn = new OpenAiChatClient(config, new Gson())
                    .complete(request, events::add, new CancellationSignal())
                    .join();
            ModelContent.ToolUse tool = turn.toolUses().getFirst();
            assertEquals("call_x", tool.id());
            assertEquals(42, tool.input().get("value").getAsInt());
            assertFalse(requestBody.get().contains("test-secret"));
            assertEquals(1, turn.usage().cacheReadTokens());
            assertTrue(events.stream().anyMatch(ModelEvent.AttemptStarted.class::isInstance));
            assertTrue(events.stream().anyMatch(ModelEvent.ResponseStarted.class::isInstance));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsAStalledCompleteResponseToModelTimeout() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            headersSent.countDown();
            try {
                releaseServer.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
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
                    128_000,
                    512,
                    Duration.ofSeconds(2),
                    Duration.ofMillis(150));
            ModelRequest request = new ModelRequest(
                    "Use tools.", List.of(ModelMessage.userText("fact")), List.of(), false);
            List<ModelEvent> events = new ArrayList<>();

            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> new OpenAiChatClient(config, new Gson())
                            .complete(request, events::add, new CancellationSignal())
                            .join());

            assertTrue(headersSent.await(1, TimeUnit.SECONDS));
            ModelClientException modelFailure = assertInstanceOf(
                    ModelClientException.class, failure.getCause());
            assertEquals("model_timeout", modelFailure.failure().code());
            assertTrue(events.stream().anyMatch(ModelEvent.AttemptStarted.class::isInstance));
            assertTrue(events.stream().anyMatch(ModelEvent.ResponseStarted.class::isInstance));
            assertFalse(events.stream().anyMatch(ModelEvent.TextDelta.class::isInstance));
        } finally {
            releaseServer.countDown();
            server.stop(0);
        }
    }
}
