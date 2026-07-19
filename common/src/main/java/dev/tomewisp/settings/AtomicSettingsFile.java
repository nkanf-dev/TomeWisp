package dev.tomewisp.settings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Same-directory atomic replacement mechanics with no domain or schema authority. */
public final class AtomicSettingsFile {
    @FunctionalInterface
    interface AtomicMove {
        void move(Path source, Path target) throws IOException;
    }

    private final AtomicMove move;

    public AtomicSettingsFile() {
        this((source, target) -> Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING));
    }

    AtomicSettingsFile(AtomicMove move) {
        this.move = Objects.requireNonNull(move, "move");
    }

    public void replace(Path target, String contents) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(contents, "contents");
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new SettingsWriteException();
        }
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(
                    parent, "." + normalized.getFileName() + "-", ".tmp");
            byte[] encoded = contents.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            move.move(temporary, normalized);
            temporary = null;
        } catch (IOException | RuntimeException failure) {
            throw new SettingsWriteException();
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The target remains unchanged; cleanup is best-effort by contract.
                }
            }
        }
    }
}
