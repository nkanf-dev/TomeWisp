package dev.openallay.integration.patchouli;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PatchouliMultiblock(
        String id, List<Block> blocks, String provenance, EvidenceMetadata evidence) {
    public PatchouliMultiblock {
        blocks = List.copyOf(blocks);
        java.util.Objects.requireNonNull(evidence, "evidence");
    }

    public PatchouliMultiblock(String id, List<Block> blocks, String provenance) {
        this(
                id,
                blocks,
                provenance,
                new EvidenceMetadata(
                        DataAuthority.DETERMINISTIC_TEST,
                        DataCompleteness.COMPLETE,
                        Instant.EPOCH,
                        "openallay:test_fixture",
                        "openallay:patchouli_fixture",
                        "test",
                        "common-test",
                        Map.of("openallay:fixture_provenance", provenance)));
    }

    public record Block(int x, int y, int z, String state) {}
}
