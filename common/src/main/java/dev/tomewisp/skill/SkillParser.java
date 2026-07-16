package dev.tomewisp.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SkillParser {
    public SkillDocument parse(SkillSource source) {
        String entry = source.files().get(source.entryPath());
        if (entry == null) {
            throw new IllegalArgumentException("Missing Skill entry " + source.entryPath());
        }
        for (String path : source.files().keySet()) {
            String normalized = SkillSource.normalize(path);
            if (normalized.contains("/scripts/") || normalized.startsWith("scripts/")) {
                throw new IllegalArgumentException("Skill scripts are not supported: " + path);
            }
        }
        ParsedFrontmatter parsed = frontmatter(entry);
        String root = source.entryPath().substring(0, source.entryPath().lastIndexOf('/') + 1);
        List<String> referencePaths = parsed.list("references");
        Map<String, String> references = new LinkedHashMap<>();
        for (String reference : referencePaths) {
            if (looksLikeUrl(reference)) {
                throw new IllegalArgumentException("Skill references cannot be URLs: " + reference);
            }
            String path = SkillSource.normalize(root + reference);
            if (!path.startsWith(root)) {
                throw new IllegalArgumentException("Skill reference escapes its root: " + reference);
            }
            String content = source.files().get(path);
            if (content == null) {
                throw new IllegalArgumentException("Missing Skill reference: " + reference);
            }
            references.put(reference, content);
        }
        SkillMetadata metadata = new SkillMetadata(
                parsed.scalar("name"),
                parsed.scalar("description"),
                Set.copyOf(parsed.list("required-mods")),
                Set.copyOf(parsed.list("allowed-tools")),
                referencePaths,
                source.provenance() + ":" + source.entryPath());
        return new SkillDocument(metadata, parsed.body(), references);
    }

    private static ParsedFrontmatter frontmatter(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.startsWith("---\n")) {
            throw new IllegalArgumentException("Skill is missing YAML frontmatter");
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) {
            throw new IllegalArgumentException("Skill frontmatter is not terminated");
        }
        Map<String, String> scalars = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        String activeList = null;
        for (String raw : normalized.substring(4, end).split("\n", -1)) {
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) {
                continue;
            }
            if (raw.startsWith("  - ") || raw.startsWith("- ")) {
                if (activeList == null) {
                    throw new IllegalArgumentException("Frontmatter list has no key");
                }
                lists.get(activeList).add(unquote(raw.substring(raw.indexOf('-') + 1).trim()));
                continue;
            }
            int separator = raw.indexOf(':');
            if (separator <= 0) {
                throw new IllegalArgumentException("Invalid frontmatter line: " + raw);
            }
            String key = raw.substring(0, separator).trim();
            String content = raw.substring(separator + 1).trim();
            activeList = null;
            if (content.isEmpty()) {
                activeList = key;
                lists.put(key, new ArrayList<>());
            } else if (content.startsWith("[") && content.endsWith("]")) {
                List<String> values = new ArrayList<>();
                String inside = content.substring(1, content.length() - 1).trim();
                if (!inside.isEmpty()) {
                    for (String item : inside.split(",")) {
                        values.add(unquote(item.trim()));
                    }
                }
                lists.put(key, values);
            } else {
                scalars.put(key, unquote(content));
            }
        }
        return new ParsedFrontmatter(scalars, lists, normalized.substring(end + 5).strip());
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean looksLikeUrl(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("file:");
    }

    private record ParsedFrontmatter(
            Map<String, String> scalars, Map<String, List<String>> lists, String body) {
        private String scalar(String key) {
            String value = scalars.get(key);
            if (value == null) {
                throw new IllegalArgumentException("Missing Skill frontmatter field: " + key);
            }
            return value;
        }

        private List<String> list(String key) {
            return List.copyOf(lists.getOrDefault(key, List.of()));
        }
    }
}
