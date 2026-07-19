package dev.openallay.trace.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.tool.ToolResult;
import dev.openallay.context.EvidenceBearing;
import dev.openallay.context.EvidenceMetadata;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ToolCodecAndNormalizerTest {
    record Input(int count) {}

    record Output(String zeta, String alpha, List<Integer> complete) {}

    record UngroundedOutput(String fact, List<EvidenceMetadata> evidence)
            implements EvidenceBearing {}

    private final Gson gson = new Gson();

    @Test
    void decodesRecordsAndReturnsInvalidArgumentsOnConversionFailure() {
        ToolArgumentCodec codec = new ToolArgumentCodec(gson);
        ToolResult.Success<Input> success = assertInstanceOf(
                ToolResult.Success.class,
                codec.decode(object("{\"count\":3}"), Input.class));
        assertEquals(3, success.value().count());

        ToolResult.Failure<Input> failure = assertInstanceOf(
                ToolResult.Failure.class,
                codec.decode(object("{\"count\":{}}"), Input.class));
        assertEquals("invalid_arguments", failure.code());
    }

    @Test
    void canonicalizationSortsObjectsAndPreservesCompleteArraysAndStrings() {
        ToolResultNormalizer normalizer = new ToolResultNormalizer(gson);
        JsonObject value = normalizer.normalize(
                new ToolResult.Success<>(new Output("unabridged", "first", List.of(3, 2, 1))),
                Output.class);

        assertEquals(List.of("outputType", "status", "value"), new ArrayList<>(value.keySet()));
        assertEquals(
                List.of("alpha", "complete", "zeta"),
                new ArrayList<>(value.getAsJsonObject("value").keySet()));
        assertEquals("unabridged", value.getAsJsonObject("value").get("zeta").getAsString());
        assertEquals(3, value.getAsJsonObject("value").getAsJsonArray("complete").size());
    }

    @Test
    void rejectsSuccessfulGroundedOutputsWithoutEvidence() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ToolResultNormalizer(gson).normalize(
                        new ToolResult.Success<>(new UngroundedOutput("claim", List.of())),
                        UngroundedOutput.class));

        assertEquals("Grounded tool output has no evidence", failure.getMessage());
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
