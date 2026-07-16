package dev.tomewisp.trace.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.trace.model.AgentTrace;
import dev.tomewisp.trace.model.ToolCallStep;
import java.io.StringReader;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TraceParserTest {
    private final TraceParser parser = new TraceParser();

    @Test
    void parsesValidSchemaOneTrace() {
        ToolResult.Success<AgentTrace> result = success(validTrace());

        assertEquals("iron-recipe", result.value().id());
        assertEquals(Set.of(ContextCapability.RECIPES), result.value().requiredContext());
        assertEquals("tomewisp:find_recipes", ((ToolCallStep) result.value().steps().getFirst()).tool());
        assertEquals(2, result.value().steps().size());
    }

    @Test
    void rejectsUnknownTopLevelAndStepFields() {
        assertInvalid(validTrace().replace("\"steps\":", "\"extra\":true,\"steps\":"));
        assertInvalid(validTrace().replace("\"tool\":", "\"extra\":true,\"tool\":"));
    }

    @Test
    void rejectsUnsupportedSchemaAndDuplicateOrBlankIds() {
        assertInvalid(validTrace().replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
        assertInvalid(validTrace().replace("\"id\":\"iron-recipe\"", "\"id\":\"\""));
        assertInvalid(validTrace().replace(
                "\"id\":\"iron-recipe\"", "\"id\":\"first\",\"id\":\"second\""));
    }

    @Test
    void rejectsUnknownStepsInvalidToolIdsAndMissingExpectations() {
        assertInvalid(validTrace().replace("tool_call", "model_thought"));
        assertInvalid(validTrace().replace("tomewisp:find_recipes", "invalid tool"));
        assertInvalid(validTrace().replace(
                "\"expect\":{\"status\":\"success\",\"match\":\"contains\",\"value\":{\"outputItem\":\"minecraft:iron_ingot\"}}",
                "\"expect\":{\"status\":\"success\",\"match\":\"contains\"}"));
    }

    @Test
    void rejectsDuplicateCapabilitiesAndMalformedExpectationShapes() {
        assertInvalid(validTrace().replace(
                "[\"recipes\"]", "[\"recipes\",\"recipes\"]"));
        assertInvalid(validTrace().replace(
                "\"match\":\"contains\",\"value\":{\"outputItem\":\"minecraft:iron_ingot\"}",
                "\"match\":\"schema\",\"value\":{}"));
    }

    @SuppressWarnings("unchecked")
    private ToolResult.Success<AgentTrace> success(String json) {
        return (ToolResult.Success<AgentTrace>)
                assertInstanceOf(ToolResult.Success.class, parser.parse(new StringReader(json)));
    }

    private void assertInvalid(String json) {
        ToolResult.Failure<AgentTrace> result = assertInstanceOf(
                ToolResult.Failure.class, parser.parse(new StringReader(json)));
        assertEquals("invalid_trace", result.code());
    }

    private static String validTrace() {
        return """
                {
                  "schemaVersion":1,
                  "id":"iron-recipe",
                  "userMessage":"铁锭怎么做？",
                  "requiredContext":["recipes"],
                  "steps":[
                    {
                      "type":"tool_call",
                      "tool":"tomewisp:find_recipes",
                      "arguments":{"outputItem":"minecraft:iron_ingot"},
                      "expect":{"status":"success","match":"contains","value":{"outputItem":"minecraft:iron_ingot"}}
                    },
                    {"type":"assistant_message","content":"使用真实配方结果回答。"}
                  ]
                }
                """;
    }
}
