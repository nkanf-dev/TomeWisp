package dev.tomewisp.model.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

final class ModelMetadataResolutionTest {
    private static final ModelMetadata METADATA = new ModelMetadata(
            "openrouter",
            "anthropic/claude-sonnet",
            "anthropic/claude-sonnet-4.5",
            256_000,
            64_000,
            Instant.parse("2026-07-18T12:00:00Z"));

    @Test
    void explicitLimitsOutrankDiscoveredValues() {
        ModelMetadataResolution explicit = ModelMetadataResolution.resolved(
                METADATA, 512_000, 8_192);
        ModelMetadataResolution discovered = ModelMetadataResolution.resolved(
                METADATA, null, null);

        assertEquals(512_000, explicit.contextWindowTokens());
        assertEquals(8_192, explicit.maxOutputTokens());
        assertEquals(256_000, discovered.contextWindowTokens());
        assertEquals(64_000, discovered.maxOutputTokens());
        assertEquals(METADATA, explicit.metadata());
        assertNull(explicit.failure());
    }

    @Test
    void missingPublishedOutputLimitRemainsUnknown() {
        ModelMetadata noOutput = new ModelMetadata(
                "openrouter", "vendor/model", "vendor/model", 128_000, null, Instant.EPOCH);

        assertNull(ModelMetadataResolution.resolved(
                noOutput, null, null).maxOutputTokens());
        assertEquals(4_096, ModelMetadataResolution.resolved(
                noOutput, null, 4_096).maxOutputTokens());
    }

    @Test
    void invalidLimitsAndMixedFailureStateAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                ModelMetadataResolution.resolved(METADATA, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ModelMetadataResolution(
                1, null, null, new dev.tomewisp.guide.GuideFailure("x", "x")));
    }
}
