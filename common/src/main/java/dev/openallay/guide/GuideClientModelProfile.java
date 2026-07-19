package dev.openallay.guide;

/** Redacted client-profile summary exposed to GuideService and player UI. */
public record GuideClientModelProfile(
        String id,
        String displayName,
        boolean enabled,
        boolean available,
        String modelIdentifier,
        GuideFailure failure) {
    public GuideClientModelProfile {
        if (id == null || !id.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid model profile id");
        }
        if (displayName == null || displayName.isBlank()
                || modelIdentifier == null || modelIdentifier.isBlank()) {
            throw new IllegalArgumentException("model profile labels must not be blank");
        }
        if (available == (failure != null)) {
            throw new IllegalArgumentException(
                    "available profile must not have a failure and unavailable profile must have one");
        }
        if (available && !enabled) {
            throw new IllegalArgumentException("disabled profile cannot be available");
        }
    }
}
