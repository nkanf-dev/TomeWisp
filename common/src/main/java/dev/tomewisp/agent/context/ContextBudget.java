package dev.tomewisp.agent.context;

/** Total model context window with one output turn and one continuation reserved. */
public record ContextBudget(int contextWindowTokens, int maxOutputTokens) {
    public ContextBudget {
        if (contextWindowTokens <= 0) {
            throw new IllegalArgumentException("contextWindowTokens must be positive");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        long reserved = (long) maxOutputTokens * 2L;
        if (reserved >= contextWindowTokens) {
            throw new IllegalArgumentException(
                    "contextWindowTokens must exceed two maxOutputTokens reserves");
        }
    }

    public int reservedTokens() {
        return Math.multiplyExact(maxOutputTokens, 2);
    }

    public int inputTokens() {
        return contextWindowTokens - reservedTokens();
    }
}
