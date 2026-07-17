package dev.tomewisp.guide.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideTopology;
import dev.tomewisp.testing.GroundedTestFixtures;
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
                List.of("tomewisp:search_recipes"),
                List.of(GroundedTestFixtures.serverEvidence()),
                GuideRequestStatus.COMPLETED,
                Map.of("total", 10L),
                Map.of("result", "abc"));

        String encoded = new GuideE2EReportJson(new Gson()).encode(report, Set.of(secret));

        assertFalse(encoded.contains(secret));
        assertTrue(encoded.contains("[REDACTED]"));
        assertTrue(encoded.contains("minecraft:recipe_manager"));
    }
}
