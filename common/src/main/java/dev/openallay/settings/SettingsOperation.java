package dev.openallay.settings;

import java.util.Objects;

/** One foreground settings action; provider reads are explicitly cancellable. */
public record SettingsOperation(Kind kind, String targetId, boolean cancellable) {
    public enum Kind {
        IDLE,
        SAVING_MODELS,
        RELOADING_MODELS,
        SAVING_CAPABILITIES,
        RELOADING_CAPABILITIES,
        SAVING_RECIPES,
        RELOADING_RECIPES,
        SAVING_TOOL,
        RELOADING_TOOL,
        SAVING_SKILL,
        DELETING_SKILL_OVERRIDE,
        RELOADING_SKILLS,
        SAVING_DISPLAY,
        RELOADING_DISPLAY,
        DELETING_CURRENT_HISTORY,
        DELETING_ACTOR_HISTORY,
        RESETTING_HISTORY_DATABASE,
        REFRESHING_METADATA,
        FETCHING_MODEL_CATALOG,
        TESTING_CONNECTION
    }

    public SettingsOperation {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.IDLE && (targetId != null || cancellable)) {
            throw new IllegalArgumentException("idle settings operation has no target or cancellation");
        }
        if (cancellable != (kind == Kind.TESTING_CONNECTION
                || kind == Kind.FETCHING_MODEL_CATALOG)) {
            throw new IllegalArgumentException("only provider reads are cancellable");
        }
        if (targetId != null && targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must be null or nonblank");
        }
    }

    public static SettingsOperation idle() {
        return new SettingsOperation(Kind.IDLE, null, false);
    }

    public static SettingsOperation models(Kind kind) {
        return new SettingsOperation(kind, null, false);
    }

    public static SettingsOperation probe(String profileId) {
        return new SettingsOperation(Kind.TESTING_CONNECTION, profileId, true);
    }

    public static SettingsOperation catalog(String profileId) {
        return new SettingsOperation(Kind.FETCHING_MODEL_CATALOG, profileId, true);
    }

    public static SettingsOperation domain(Kind kind) {
        if (kind == Kind.IDLE || kind == Kind.TESTING_CONNECTION
                || kind == Kind.FETCHING_MODEL_CATALOG) {
            throw new IllegalArgumentException("domain operation must be a non-cancellable mutation");
        }
        return new SettingsOperation(kind, null, false);
    }
}
