package dev.openallay.model.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.agent.tool.AgentToolResult;
import dev.openallay.agent.tool.ModelToolResultView;
import dev.openallay.agent.tool.ToolResultDiagnostics;
import dev.openallay.agent.tool.ToolUiReference;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRequest;
import dev.openallay.model.ModelRole;
import dev.openallay.model.config.ModelConfig;
import dev.openallay.model.config.ModelProtocol;
import dev.openallay.model.config.SecretValue;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AnthropicJsonCodecTest {
    @Test
    void sendsSemanticToolTextVerbatimAndNeverSerializesExactTruth() {
        AgentToolResult result = projectedLargeTruth();
        ModelContent.ToolResult content = new ModelContent.ToolResult(
                "call_1", result.modelView().text(), "/result/r_test", false);
        ModelRequest request = new ModelRequest("system", List.of(
                new ModelMessage(ModelRole.USER, List.of(content))), List.of(), false);

        String body = new AnthropicJsonCodec(new Gson()).requestBody(config(), request);
        JsonObject block = JsonParser.parseString(body).getAsJsonObject()
                .getAsJsonArray("messages").get(0).getAsJsonObject()
                .getAsJsonArray("content").get(0).getAsJsonObject();

        assertEquals("call_1", block.get("tool_use_id").getAsString());
        assertEquals(result.modelView().text(), block.get("content").getAsString());
        assertFalse(body.contains("EXACT_SECRET_MARKER"));
        assertTrue(body.length() < 1_000);
    }

    private static AgentToolResult projectedLargeTruth() {
        JsonObject normalized = new JsonObject();
        normalized.addProperty("status", "success");
        normalized.addProperty("value", "EXACT_SECRET_MARKER".repeat(20_000));
        return new AgentToolResult(
                "openallay:resource_read", normalized,
                new ModelToolResultView("status: success\nresult: /result/r_test\nitems: 2"),
                ToolUiReference.none(),
                new ToolResultDiagnostics(normalized.toString().length(), 52, "g1", Instant.EPOCH), false);
    }

    private static ModelConfig config() {
        return new ModelConfig(true, ModelProtocol.ANTHROPIC_MESSAGES, URI.create("https://example.invalid/v1/"),
                "test", SecretValue.of("secret"), 100_000, 512,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
}
