package dev.tomewisp.tool.gamestate;

import java.util.Locale;

/** Non-mutating query equivalents. No command text is ever parsed or executed. */
public enum WorldQueryOperation {
    TIME,
    WEATHER,
    DIFFICULTY,
    WORLD_BORDER,
    SPAWN;

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static WorldQueryOperation parse(String value) {
        for (WorldQueryOperation operation : values()) {
            if (operation.serializedName().equals(value)) {
                return operation;
            }
        }
        throw new IllegalArgumentException("unknown read-only world query");
    }
}
