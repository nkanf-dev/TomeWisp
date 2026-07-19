package dev.tomewisp.capability;

import java.util.Objects;
import java.util.regex.Pattern;

/** Code-owned, presentation-only description of one settings capability card. */
public record CapabilitySettingsDescriptor(
        String id,
        CapabilityKind kind,
        String titleKey,
        String descriptionKey,
        CapabilityChildPage childPage) {
    private static final Pattern IDENTITY =
            Pattern.compile("[a-z0-9][a-z0-9_.-]*(?::[a-z0-9_][a-z0-9_./-]*)?");
    private static final Pattern LOCALIZATION_KEY =
            Pattern.compile("[a-z0-9][a-z0-9_.-]*");

    public CapabilitySettingsDescriptor {
        id = requireIdentity(id, "capability id");
        Objects.requireNonNull(kind, "kind");
        titleKey = requireLocalizationKey(titleKey, "titleKey");
        descriptionKey = requireLocalizationKey(descriptionKey, "descriptionKey");
    }

    public static String requireIdentity(String value, String name) {
        if (value == null || !IDENTITY.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
        return value;
    }

    private static String requireLocalizationKey(String value, String name) {
        if (value == null || !LOCALIZATION_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
        return value;
    }
}
