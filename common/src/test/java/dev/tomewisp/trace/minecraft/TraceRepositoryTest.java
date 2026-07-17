package dev.tomewisp.trace.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.trace.json.TraceParser;
import java.io.StringReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TraceRepositoryTest {
    private final TraceRepository repository = new TraceRepository(new TraceParser());

    @Test
    void loadsSortedIdsAndRequiresFilenameToMatchTraceId() {
        ToolResult.Success<TraceRepository.LoadedTraces> loaded = success(repository.load(List.of(
                source("example:agent_traces/zeta.json", trace("zeta")),
                source("tomewisp:agent_traces/alpha.json", trace("alpha")))));
        assertEquals(List.of("alpha", "zeta"), loaded.value().ids());

        ToolResult.Failure<TraceRepository.LoadedTraces> mismatch = failure(repository.load(
                List.of(source("tomewisp:agent_traces/wrong.json", trace("actual")))));
        assertEquals("invalid_trace", mismatch.code());
    }

    @Test
    void rejectsDuplicateIdsAcrossNamespacesAndInvalidJson() {
        ToolResult.Failure<TraceRepository.LoadedTraces> duplicate = failure(repository.load(List.of(
                source("first:agent_traces/same.json", trace("same")),
                source("second:agent_traces/same.json", trace("same")))));
        assertEquals("invalid_trace", duplicate.code());

        ToolResult.Failure<TraceRepository.LoadedTraces> invalid = failure(repository.load(
                List.of(source("tomewisp:agent_traces/broken.json", "{}"))));
        assertEquals("invalid_trace", invalid.code());
    }

    @Test
    void bundledTracesAreStrictlyValidAndDiscoverable() {
        List<String> ids = List.of(
                "find-recipes-compatibility",
                "iron-block-craftability",
                "iron-ingot-recipe",
                "platform-info",
                "player-context");
        List<TraceRepository.TraceSource> sources = ids.stream()
                .map(id -> new TraceRepository.TraceSource(
                        "tomewisp:agent_traces/" + id + ".json",
                        () -> new InputStreamReader(
                                java.util.Objects.requireNonNull(getClass()
                                        .getResourceAsStream(
                                                "/data/tomewisp/agent_traces/" + id + ".json")),
                                StandardCharsets.UTF_8)))
                .toList();

        assertEquals(ids, success(repository.load(sources)).value().ids());
    }

    private static TraceRepository.TraceSource source(String name, String json) {
        return new TraceRepository.TraceSource(name, () -> new StringReader(json));
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Success<TraceRepository.LoadedTraces> success(
            ToolResult<TraceRepository.LoadedTraces> result) {
        return (ToolResult.Success<TraceRepository.LoadedTraces>)
                assertInstanceOf(ToolResult.Success.class, result);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Failure<TraceRepository.LoadedTraces> failure(
            ToolResult<TraceRepository.LoadedTraces> result) {
        return (ToolResult.Failure<TraceRepository.LoadedTraces>)
                assertInstanceOf(ToolResult.Failure.class, result);
    }

    private static String trace(String id) {
        return """
                {
                  "schemaVersion": 1,
                  "id": "%s",
                  "userMessage": "test",
                  "requiredContext": [],
                  "steps": [{"type": "assistant_message", "content": "answer"}]
                }
                """
                .formatted(id);
    }
}
