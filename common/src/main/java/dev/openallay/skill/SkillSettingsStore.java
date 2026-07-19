package dev.openallay.skill;

import dev.openallay.settings.AtomicSettingsFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Player-driven atomic CRUD for local Skill overrides; this is not exposed as an Agent Tool. */
public final class SkillSettingsStore {
    private final Path root;
    private final SkillParser parser;
    private final AtomicSettingsFile atomicFile = new AtomicSettingsFile();

    public SkillSettingsStore(Path root, SkillParser parser) {
        this.root = java.util.Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
    }

    /** Creates a complete local copy of one immutable bundled package. */
    public synchronized Path createOverride(SkillSource bundled) {
        java.util.Objects.requireNonNull(bundled, "bundled");
        if (bundled.origin() != SkillSource.Origin.BUNDLED) {
            throw new IllegalArgumentException("Only bundled Skills can be copied as overrides");
        }
        SkillDocument document = parser.parse(bundled);
        String name = document.metadata().name();
        Path trustedRoot = ensureRoot();
        Path target = child(trustedRoot, name);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("A local Skill override already exists: " + name);
        }
        Path staging = null;
        try {
            staging = Files.createTempDirectory(trustedRoot, "." + name + "-");
            String sourceRoot = bundled.entryPath().substring(0, bundled.entryPath().lastIndexOf('/') + 1);
            for (Map.Entry<String, String> file : bundled.files().entrySet()) {
                if (!file.getKey().startsWith(sourceRoot)) {
                    throw new IllegalArgumentException("Bundled Skill file escapes its package root");
                }
                String relative = file.getKey().substring(sourceRoot.length());
                Path destination = staging.resolve(relative).normalize();
                if (!destination.startsWith(staging)) {
                    throw new IllegalArgumentException("Bundled Skill path escapes its package root");
                }
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, file.getValue(), StandardOpenOption.CREATE_NEW);
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            staging = null;
            return target;
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to create local Skill override", failure);
        } finally {
            deleteTreeQuietly(staging);
        }
    }

    /** Validates and atomically replaces only the local override's SKILL.md entry. */
    public synchronized void editOverride(String name, String skillMarkdown) {
        validateName(name);
        java.util.Objects.requireNonNull(skillMarkdown, "skillMarkdown");
        Path trustedRoot = requireRoot();
        Path targetDirectory = requireLocalDirectory(trustedRoot, name);
        FilesystemSkillLoader.LoadResult loaded = new FilesystemSkillLoader().load(trustedRoot);
        SkillSource current = loaded.sources().stream()
                .filter(source -> source.directoryName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Local Skill override is not editable: " + name));
        Map<String, String> candidateFiles = new LinkedHashMap<>(current.files());
        candidateFiles.put(name + "/SKILL.md", skillMarkdown);
        SkillSource candidate = new SkillSource(
                current.provenance(),
                name + "/SKILL.md",
                candidateFiles,
                SkillSource.Origin.LOCAL);
        parser.parse(candidate);
        atomicFile.replace(targetDirectory.resolve("SKILL.md"), skillMarkdown);
    }

    /** Atomically removes a local override directory, revealing any bundled Skill on reload. */
    public synchronized void deleteOverride(String name) {
        validateName(name);
        Path trustedRoot = requireRoot();
        Path target = requireLocalDirectory(trustedRoot, name);
        Path tombstone = trustedRoot.resolve(".deleted-" + name + "-" + java.util.UUID.randomUUID())
                .normalize();
        if (!tombstone.startsWith(trustedRoot)) {
            throw new IllegalStateException("Invalid Skill deletion target");
        }
        try {
            Files.move(target, tombstone, StandardCopyOption.ATOMIC_MOVE);
            deleteTree(tombstone);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to delete local Skill override", failure);
        }
    }

    public synchronized boolean hasOverride(String name) {
        validateName(name);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            return false;
        }
        Path target = child(root, name);
        return Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target);
    }

    private Path ensureRoot() {
        try {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(root)) {
                throw new IllegalStateException("Skill settings root cannot be a symbolic link");
            }
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) {
                throw new IllegalStateException("Skill settings root cannot be a symbolic link");
            }
            return root.toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to prepare Skill settings root", failure);
        }
    }

    private Path requireRoot() {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IllegalStateException("Skill settings root is unavailable");
        }
        try {
            return root.toRealPath();
        } catch (IOException failure) {
            throw new IllegalStateException("Skill settings root is unavailable", failure);
        }
    }

    private static Path requireLocalDirectory(Path trustedRoot, String name) {
        Path target = child(trustedRoot, name);
        if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Local Skill override does not exist: " + name);
        }
        try {
            if (!target.toRealPath().startsWith(trustedRoot)) {
                throw new IllegalStateException("Local Skill override escapes its settings root");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Local Skill override is unavailable", failure);
        }
        return target;
    }

    private static Path child(Path trustedRoot, String name) {
        validateName(name);
        Path child = trustedRoot.resolve(name).normalize();
        if (!child.getParent().equals(trustedRoot)) {
            throw new IllegalArgumentException("Skill name escapes its settings root");
        }
        return child;
    }

    private static void validateName(String name) {
        if (name == null || name.length() > 64 || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("Invalid Skill name: " + name);
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static void deleteTreeQuietly(Path path) {
        try {
            deleteTree(path);
        } catch (IOException ignored) {
            // A failed create never publishes the staging directory as an active override.
        }
    }
}
