package dev.openallay.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRole;
import dev.openallay.model.ModelToolDefinition;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class Utf8ContextTokenEstimatorTest {
    private final Utf8ContextTokenEstimator estimator = new Utf8ContextTokenEstimator();

    @Test
    void validatesWindowAndDoubleOutputReserve() {
        ContextBudget budget = new ContextBudget(128_000, 8_192);

        assertEquals(111_616, budget.inputTokens());
        assertEquals(16_384, budget.reservedTokens());
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(100, 0));
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(100, 50));
    }

    @Test
    void deterministicallyAccountsForUnicodeStructureToolsAndJson() {
        JsonObject input = new JsonObject();
        input.addProperty("resource", "农夫乐事:苹果酒");
        ModelMessage message = new ModelMessage(
                ModelRole.ASSISTANT,
                List.of(
                        new ModelContent.Text("先查询配方。"),
                        new ModelContent.ToolUse("call_1", "openallay__get_recipe", input)));
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        ModelToolDefinition tool =
                new ModelToolDefinition("openallay__get_recipe", "读取精确配方", schema);

        int first = estimator.estimate("系统约束", List.of(message), List.of(tool));
        int second = estimator.estimate("系统约束", List.of(message), List.of(tool));

        assertEquals(first, second);
        assertTrue(first >= "系统约束".getBytes(StandardCharsets.UTF_8).length);

        input.addProperty("ignored_after_copy", "x".repeat(10_000));
        schema.addProperty("ignored_after_copy", "y".repeat(10_000));
        assertEquals(first, estimator.estimate("系统约束", List.of(message), List.of(tool)));
    }

    @Test
    void everyAdditionalPayloadByteConsumesBudget() {
        int shortEstimate = estimator.estimate(
                "system", List.of(ModelMessage.userText("a")), List.of());
        int longEstimate = estimator.estimate(
                "system", List.of(ModelMessage.userText("a".repeat(100))), List.of());

        assertEquals(99, longEstimate - shortEstimate);
    }
}
