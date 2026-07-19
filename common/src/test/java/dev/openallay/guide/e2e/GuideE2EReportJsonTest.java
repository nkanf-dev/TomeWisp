package dev.openallay.guide.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.openallay.guide.GuideRequestStatus;
import dev.openallay.guide.GuideTopology;
import dev.openallay.testing.GroundedTestFixtures;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GuideE2EReportJsonTest {
    @Test
    void canonicalReportRetainsEvidenceAndRedactsConfiguredSecrets() {
        String secret = "fixture-secret-value-123456";
        GuideE2EReport report = new GuideE2EReport(
                "fabric",
                "26.2",
                "test",
                "client_local_" + secret,
                GuideTopology.CLIENT_LOCAL,
                UUID.randomUUID(),
                "main",
                List.of(GuideRequestStatus.PREPARING, GuideRequestStatus.COMPLETED),
                List.of("openallay:search_recipes"),
                List.of(new GuideE2EReport.ToolProbe(
                        "openallay:search_recipes",
                        dev.openallay.guide.GuideToolStatus.SUCCEEDED,
                        null,
                        null)),
                List.of(GroundedTestFixtures.serverEvidence()),
                List.of("assistant", "tool", "assistant"),
                Map.of("assistantSegments", 2L, "semanticFallbacks", 1L),
                List.of("semantic_component_unsupported"),
                List.of("status_badge"),
                "IDLE",
                Map.of("loadedRequests", 1L, "totalRequests", 1L),
                GuideRequestStatus.COMPLETED,
                null,
                null,
                Map.of("total", 10L),
                Map.of("result", "abc"));

        String encoded = new GuideE2EReportJson(new Gson()).encode(report, Set.of(secret));

        assertFalse(encoded.contains(secret));
        assertTrue(encoded.contains("[REDACTED]"));
        assertTrue(encoded.contains("minecraft:recipe_manager"));
        assertTrue(encoded.contains("semantic_component_unsupported"));
        assertFalse(encoded.contains("component payload"));
    }
}
