package dev.tomewisp.settings.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.ModelClientException;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelFailure;
import dev.tomewisp.model.ModelRequest;
import dev.tomewisp.model.ModelTurn;
import dev.tomewisp.model.ModelUsage;
import dev.tomewisp.model.config.ModelConfig;
import dev.tomewisp.model.config.ModelProfileDefinition;
import dev.tomewisp.model.config.ModelProtocol;
import dev.tomewisp.model.config.ResolvedModelProfile;
import dev.tomewisp.model.config.SecretValue;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class ModelConnectionProbeTest {
    private static final Instant COMPLETED_AT = Instant.parse("2026-07-18T08:00:00Z");

    @Test
    void sendsOneContextFreeBoundedRequestAndDiscardsAssistantText() {
        RecordingModel model = new RecordingModel(CompletableFuture.completedFuture(
                turn(List.of(new ModelContent.Text("SENSITIVE-OUTPUT")))));
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        List<ModelConfig> configs = new ArrayList<>();
        ModelConnectionProbe probe = new ModelConnectionProbe(config -> {
            configs.add(config);
            return model;
        }, Clock.fixed(COMPLETED_AT, ZoneOffset.UTC), () -> nanos.getAndAdd(25_000_000L));

        ModelConnectionResult result = probe.test(profile(8_192), new CancellationSignal()).join();

        assertEquals(64, configs.getFirst().maxOutputTokens());
        ModelRequest request = model.requests.getFirst();
        assertEquals(List.of(), request.tools());
        assertFalse(request.stream());
        assertEquals("tomewisp-settings-probe", request.sessionKey());
        assertEquals("Reply exactly OK.",
                ((ModelContent.Text) request.messages().getFirst().content().getFirst()).text());
        ModelConnectionResult.Success success = assertInstanceOf(
                ModelConnectionResult.Success.class, result);
        assertEquals("main", success.profileId());
        assertEquals(ModelProtocol.OPENAI_CHAT, success.protocol());
        assertEquals("https://provider.example:8443", success.authority());
        assertEquals(COMPLETED_AT, success.completedAt());
        assertEquals(25, success.latencyMillis());
        assertFalse(result.toString().contains("SENSITIVE-OUTPUT"));
        assertFalse(result.toString().contains("secret-value"));
    }

    @Test
    void retainsSmallerConfiguredOutputLimit() {
        List<ModelConfig> configs = new ArrayList<>();
        ModelConnectionProbe probe = probe(config -> {
            configs.add(config);
            return completed("OK");
        });

        assertInstanceOf(ModelConnectionResult.Success.class,
                probe.test(profile(16), new CancellationSignal()).join());
        assertEquals(16, configs.getFirst().maxOutputTokens());
    }

    @Test
    void unavailableProfileSendsNothing() {
        AtomicLong calls = new AtomicLong();
        ModelConnectionProbe probe = probe(config -> {
            calls.incrementAndGet();
            return completed("OK");
        });
        ResolvedModelProfile unavailable = new ResolvedModelProfile(
                profile(64).definition(),
                null,
                new GuideFailure("model_not_configured", "Credential is absent"));

        ModelConnectionResult.Failure failure = assertInstanceOf(
                ModelConnectionResult.Failure.class,
                probe.test(unavailable, new CancellationSignal()).join());

        assertEquals("model_not_configured", failure.code());
        assertEquals(0, calls.get());
    }

    @Test
    void classifiesProviderFailuresWithoutRetainingRawMessages() {
        assertFailure(401, "connection_auth_failed");
        assertFailure(403, "connection_auth_failed");
        assertFailure(404, "connection_model_unavailable");
        assertFailure(429, "connection_rate_limited");
        assertFailure(400, "connection_protocol_failed");
        assertFailure(null, "connection_transport_failed");
    }

    @Test
    void mapsTimeoutCancellationAndInvalidTurns() {
        assertCode(failing(new ModelFailure("model_timeout", "raw timeout", null)),
                new CancellationSignal(), "connection_timeout");

        CancellationSignal cancellation = new CancellationSignal();
        cancellation.cancel();
        assertCode(failing(new ModelFailure("agent_cancelled", "raw cancel", null)),
                cancellation, "connection_cancelled");

        assertCode(new RecordingModel(CompletableFuture.completedFuture(
                        turn(List.of(new ModelContent.Text("   "))))),
                new CancellationSignal(), "connection_protocol_failed");
        assertCode(new RecordingModel(CompletableFuture.completedFuture(
                        turn(List.of(new ModelContent.ToolUse(
                                "call", "unexpected", new com.google.gson.JsonObject()))))),
                new CancellationSignal(), "connection_protocol_failed");
    }

    private static void assertFailure(Integer status, String expected) {
        assertCode(failing(new ModelFailure(
                        status != null && status == 429 ? "model_rate_limited" : "model_http_error",
                        "raw-provider-secret-message",
                        status)),
                new CancellationSignal(), expected);
    }

    private static void assertCode(
            RecordingModel model,
            CancellationSignal cancellation,
            String expected) {
        ModelConnectionResult.Failure failure = assertInstanceOf(
                ModelConnectionResult.Failure.class,
                probe(ignored -> model).test(profile(64), cancellation).join());
        assertEquals(expected, failure.code());
        assertFalse(failure.toString().contains("raw"));
        assertFalse(failure.toString().contains("secret"));
    }

    private static ModelConnectionProbe probe(
            java.util.function.Function<ModelConfig, ModelClient> factory) {
        AtomicLong nanos = new AtomicLong();
        return new ModelConnectionProbe(
                factory,
                Clock.fixed(COMPLETED_AT, ZoneOffset.UTC),
                () -> nanos.getAndAdd(1_000_000L));
    }

    private static RecordingModel failing(ModelFailure failure) {
        return new RecordingModel(CompletableFuture.failedFuture(
                new ModelClientException(failure)));
    }

    private static RecordingModel completed(String text) {
        return new RecordingModel(CompletableFuture.completedFuture(
                turn(List.of(new ModelContent.Text(text)))));
    }

    private static ResolvedModelProfile profile(int maxOutputTokens) {
        ModelProfileDefinition definition = new ModelProfileDefinition(
                "main",
                "Main",
                true,
                ModelProtocol.OPENAI_CHAT,
                URI.create("https://provider.example:8443/v1"),
                "vendor/model",
                "MODEL_KEY",
                256_000,
                maxOutputTokens,
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                null);
        return new ResolvedModelProfile(
                definition,
                new ModelConfig(
                        true,
                        definition.protocol(),
                        definition.baseUri(),
                        definition.model(),
                        SecretValue.of("secret-value"),
                        definition.contextWindowTokens(),
                        definition.maxOutputTokens(),
                        definition.connectTimeout(),
                        definition.requestTimeout()),
                null);
    }

    private static ModelTurn turn(List<ModelContent> content) {
        return new ModelTurn(
                "test", "model", content, "end_turn", ModelUsage.empty());
    }

    private static final class RecordingModel implements ModelClient {
        private final CompletableFuture<ModelTurn> response;
        private final List<ModelRequest> requests = new ArrayList<>();

        private RecordingModel(CompletableFuture<ModelTurn> response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<ModelTurn> complete(
                ModelRequest request,
                Consumer<ModelEvent> events,
                CancellationSignal cancellation) {
            requests.add(request);
            return response;
        }
    }
}
