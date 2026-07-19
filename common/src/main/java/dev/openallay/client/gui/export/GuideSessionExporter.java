package dev.openallay.client.gui.export;

import dev.openallay.guide.export.GuideSessionExportSnapshot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Writes player-requested conversation text under one fixed game-directory child. */
public final class GuideSessionExporter {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)([\\\"']?authorization[\\\"']?\\s*[:=]\\s*(?:bearer\\s+)?)"
                    + "(?:\\\"[^\\\"]*\\\"|'[^']*'|\\S+)");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)([\\\"']?(?:api[-_ ]?key|access[-_ ]?token|token|secret|password)"
                    + "[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|\\S+)");
    private static final Pattern BEARER_SECRET = Pattern.compile(
            "(?i)\\bbearer\\s+[a-z0-9._~+/=-]{12,}");
    private static final Pattern PREFIXED_SECRET = Pattern.compile(
            "(?i)\\b(?:sk|pk)-[a-z0-9_-]{12,}\\b");

    public record ExportedFile(String filename, int requestCount) {
        public ExportedFile {
            if (filename == null || !filename.matches("[a-zA-Z0-9_.-]+\\.txt")
                    || requestCount < 0) {
                throw new IllegalArgumentException("invalid exported file result");
            }
        }
    }

    private final Path gameDirectory;

    public GuideSessionExporter(Path gameDirectory) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory")
                .toAbsolutePath().normalize();
    }

    public ExportedFile export(GuideSessionExportSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        String content = format(snapshot);
        byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
        String hash = digest(encoded).substring(0, 12);
        String filename = snapshot.sessionId() + "-"
                + FILE_TIME.format(snapshot.capturedAt()) + "-" + hash + ".txt";
        Path temporary = null;
        try {
            Path root = prepareManagedRoot();
            Path destination = root.resolve(filename).normalize();
            if (!destination.getParent().equals(root)) {
                throw new IOException("export destination escaped the managed directory");
            }
            temporary = Files.createTempFile(root, ".openallay-export-", ".tmp");
            restrictPermissions(temporary);
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("atomic export publication is unavailable", unsupported);
            }
            temporary = null;
            return new ExportedFile(filename, snapshot.requests().size());
        } catch (IOException | RuntimeException failure) {
            throw new GuideSessionExportException(
                    "history_export_failed", "Unable to write the guide session export", failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The final file was never published; best-effort cleanup is sufficient.
                }
            }
        }
    }

    static String format(GuideSessionExportSnapshot snapshot) {
        StringBuilder result = new StringBuilder();
        result.append("OpenAllay conversation export\n")
                .append("Session: ").append(snapshot.sessionId()).append('\n')
                .append("Captured: ").append(snapshot.capturedAt()).append("\n\n");
        int index = 0;
        for (GuideSessionExportSnapshot.Request request : snapshot.requests()) {
            result.append("=== Request ").append(++index)
                    .append(" · ").append(request.createdAt())
                    .append(" · ").append(request.status()).append(" ===\n")
                    .append("User\n")
                    .append(redact(request.userMessage())).append("\n\n");
            for (GuideSessionExportSnapshot.Entry entry : request.timeline()) {
                switch (entry) {
                    case GuideSessionExportSnapshot.Entry.Assistant assistant -> result
                            .append(assistant.streaming() ? "Assistant (in progress)\n" : "Assistant\n")
                            .append(redact(assistant.text())).append("\n\n");
                    case GuideSessionExportSnapshot.Entry.Tool tool -> result
                            .append("Tool · ")
                            .append(safeToolName(tool.toolId()))
                            .append(" · ").append(tool.status())
                            .append("\n\n");
                }
            }
        }
        return result.toString();
    }

    static String redact(String value) {
        String safe = value == null ? "" : value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\p{Cc}&&[^\\n\\t]]", "�");
        safe = AUTHORIZATION.matcher(safe).replaceAll("$1[REDACTED]");
        safe = NAMED_SECRET.matcher(safe).replaceAll("$1[REDACTED]");
        safe = BEARER_SECRET.matcher(safe).replaceAll("Bearer [REDACTED]");
        return PREFIXED_SECRET.matcher(safe).replaceAll("[REDACTED]");
    }

    private Path prepareManagedRoot() throws IOException {
        Path realGame = gameDirectory.toRealPath();
        Path openallay = gameDirectory.resolve("openallay");
        Path exports = openallay.resolve("exports");
        rejectSymlink(openallay);
        rejectSymlink(exports);
        Files.createDirectories(exports);
        rejectSymlink(openallay);
        rejectSymlink(exports);
        Path realExports = exports.toRealPath();
        if (!realExports.startsWith(realGame)) {
            throw new IOException("managed export directory escaped the game directory");
        }
        return realExports;
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException("managed export directory contains a symbolic link");
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions
                    .fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and non-POSIX filesystems retain their platform ACL defaults.
        }
    }

    private static String safeToolName(String toolId) {
        int separator = toolId.indexOf(':');
        String value = separator < 0 ? toolId : toolId.substring(separator + 1);
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
