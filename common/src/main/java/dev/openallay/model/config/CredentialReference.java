package dev.openallay.model.config;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Qualified credential identity safe to persist and expose in redacted settings state. */
public record CredentialReference(Kind kind, String value) {
    public CredentialReference {
        Objects.requireNonNull(kind, "kind");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("credential reference value must not be blank");
        }
        switch (kind) {
            case LOCAL -> value = UUID.fromString(value).toString();
            case ENVIRONMENT -> {
                if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("invalid credential environment reference");
                }
            }
        }
    }

    public static CredentialReference local(UUID id) {
        return new CredentialReference(Kind.LOCAL, Objects.requireNonNull(id, "id").toString());
    }

    public static CredentialReference environment(String name) {
        return new CredentialReference(Kind.ENVIRONMENT, name);
    }

    public static CredentialReference parse(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("credential reference must not be null");
        }
        int separator = encoded.indexOf(':');
        if (separator <= 0 || separator == encoded.length() - 1) {
            throw new IllegalArgumentException("invalid credential reference");
        }
        Kind kind = switch (encoded.substring(0, separator).toLowerCase(Locale.ROOT)) {
            case "local" -> Kind.LOCAL;
            case "env" -> Kind.ENVIRONMENT;
            default -> throw new IllegalArgumentException("unsupported credential reference kind");
        };
        return new CredentialReference(kind, encoded.substring(separator + 1));
    }

    public String encoded() {
        return (kind == Kind.LOCAL ? "local:" : "env:") + value;
    }

    @Override
    public String toString() {
        return encoded();
    }

    public enum Kind {
        LOCAL,
        ENVIRONMENT
    }
}
