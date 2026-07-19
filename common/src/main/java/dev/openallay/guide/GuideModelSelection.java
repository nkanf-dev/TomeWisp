package dev.openallay.guide;

/** Closed, credential-free model choice stored per guide session and request. */
public record GuideModelSelection(Kind kind, String profileId) {
    public enum Kind { CLIENT, SERVER }

    public GuideModelSelection {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind == Kind.CLIENT) {
            if (profileId == null || !profileId.matches("[a-zA-Z0-9_.-]+")) {
                throw new IllegalArgumentException("client selection requires a valid profileId");
            }
        } else if (profileId != null) {
            throw new IllegalArgumentException("server selection cannot contain a profileId");
        }
    }

    public static GuideModelSelection client(String profileId) {
        return new GuideModelSelection(Kind.CLIENT, profileId);
    }

    public static GuideModelSelection server() {
        return new GuideModelSelection(Kind.SERVER, null);
    }

    public GuideModelMode modelMode() {
        return kind == Kind.CLIENT ? GuideModelMode.CLIENT : GuideModelMode.SERVER;
    }
}
