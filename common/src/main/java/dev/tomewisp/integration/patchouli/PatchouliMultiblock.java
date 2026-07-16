package dev.tomewisp.integration.patchouli;

import java.util.List;

public record PatchouliMultiblock(String id, List<Block> blocks, String provenance) {
    public PatchouliMultiblock {
        blocks = List.copyOf(blocks);
    }

    public record Block(int x, int y, int z, String state) {}
}
