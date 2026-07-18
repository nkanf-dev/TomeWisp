package dev.tomewisp.guide.semantic;

import java.util.Arrays;

public enum SemanticReferenceKind {
    ITEM("item", true),
    BLOCK("block", true),
    FLUID("fluid", true),
    ENTITY("entity", true),
    BIOME("biome", true),
    DIMENSION("dimension", true),
    TAG("tag", true),
    RECIPE("recipe", false),
    KEY("key", true),
    SOURCE("source", false),
    EVIDENCE("evidence", false);

    private final String token;
    private final boolean permitsUngroundedPresentation;

    SemanticReferenceKind(String token, boolean permitsUngroundedPresentation) {
        this.token = token;
        this.permitsUngroundedPresentation = permitsUngroundedPresentation;
    }

    public String token() {
        return token;
    }

    public boolean permitsUngroundedPresentation() {
        return permitsUngroundedPresentation;
    }

    public static SemanticReferenceKind fromToken(String token) {
        return Arrays.stream(values()).filter(value -> value.token.equals(token))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "unknown semantic reference kind"));
    }
}
