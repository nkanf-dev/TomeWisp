package dev.tomewisp.guide.history;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record GuideHistoryScope(UUID actorId, Kind kind, String scopeId) {
    public enum Kind {
        SINGLEPLAYER,
        MULTIPLAYER
    }

    public GuideHistoryScope {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(kind, "kind");
        if (scopeId == null || !scopeId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("scopeId must be a lowercase SHA-256 digest");
        }
    }

    public static GuideHistoryScope derive(UUID actorId, Kind kind, String discriminator) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(kind, "kind");
        if (discriminator == null || discriminator.isBlank()) {
            throw new IllegalArgumentException("history discriminator must not be blank");
        }
        String normalized = switch (kind) {
            case SINGLEPLAYER -> Path.of(discriminator.trim())
                    .toAbsolutePath()
                    .normalize()
                    .toString();
            case MULTIPLAYER -> discriminator.trim().toLowerCase(Locale.ROOT);
        };
        String material = actorId + "\0" + kind.name() + "\0" + normalized;
        return new GuideHistoryScope(actorId, kind, digest(material));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256", impossible);
        }
    }
}
