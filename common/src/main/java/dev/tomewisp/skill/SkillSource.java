package dev.tomewisp.skill;

import java.util.LinkedHashMap;
import java.util.Map;

public record SkillSource(
        String provenance, String entryPath, Map<String, String> files, Origin origin) {
    public enum Origin {
        BUNDLED,
        LOCAL,
        EXTERNAL
    }

    public SkillSource(String provenance, String entryPath, Map<String, String> files) {
        this(provenance, entryPath, files, Origin.EXTERNAL);
    }

    public SkillSource {
        if (provenance == null || provenance.isBlank()) {
            throw new IllegalArgumentException("Skill provenance must not be blank");
        }
        entryPath = normalize(entryPath);
        if (!entryPath.endsWith("/SKILL.md") && !entryPath.endsWith("/skill.md")) {
            throw new IllegalArgumentException("Skill entry must be SKILL.md or skill.md");
        }
        if (origin == null) {
            throw new IllegalArgumentException("Skill origin must not be null");
        }
        LinkedHashMap<String, String> normalizedFiles = new LinkedHashMap<>();
        files.forEach((path, contents) -> {
            String normalizedPath = normalize(path);
            if (contents == null) {
                throw new IllegalArgumentException("Skill file contents must not be null: " + path);
            }
            if (normalizedFiles.put(normalizedPath, contents) != null) {
                throw new IllegalArgumentException("Duplicate normalized Skill path: " + path);
            }
        });
        files = Map.copyOf(normalizedFiles);
    }

    public String directoryName() {
        int separator = entryPath.lastIndexOf('/');
        String directory = entryPath.substring(0, separator);
        int parent = directory.lastIndexOf('/');
        return directory.substring(parent + 1);
    }

    static String normalize(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")) {
            throw new IllegalArgumentException("Invalid Skill path: " + path);
        }
        java.nio.file.Path normalized = java.nio.file.Path.of(path).normalize();
        String value = normalized.toString().replace(java.io.File.separatorChar, '/');
        if (value.equals("..") || value.startsWith("../")) {
            throw new IllegalArgumentException("Skill path escapes its root: " + path);
        }
        return value;
    }
}
