package dev.tomewisp.skill;

import java.util.Map;

public record SkillSource(String provenance, String entryPath, Map<String, String> files) {
    public SkillSource {
        if (provenance == null || provenance.isBlank()) {
            throw new IllegalArgumentException("Skill provenance must not be blank");
        }
        entryPath = normalize(entryPath);
        if (!entryPath.endsWith("/SKILL.md") && !entryPath.endsWith("/skill.md")) {
            throw new IllegalArgumentException("Skill entry must be SKILL.md or skill.md");
        }
        files = Map.copyOf(files);
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
