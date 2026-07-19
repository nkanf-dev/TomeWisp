package dev.openallay.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Parser for the safe OpenAllay subset of the public Agent Skills format. */
public final class SkillParser {
    private static final Set<String> AGENT_SKILL_FIELDS = Set.of(
            "name", "description", "license", "compatibility", "metadata", "allowed-tools");
    private static final String REQUIRED_MODS_ATTRIBUTE = "openallay/required-mods";

    public SkillDocument parse(SkillSource source) {
        String entry = source.files().get(source.entryPath());
        if (entry == null) {
            throw new IllegalArgumentException("Missing Skill entry " + source.entryPath());
        }
        boolean agentSkillsFormat = source.entryPath().endsWith("/SKILL.md");
        String root = source.entryPath().substring(0, source.entryPath().lastIndexOf('/') + 1);
        validateFiles(source, root, agentSkillsFormat);
        ParsedFrontmatter parsed = frontmatter(entry);
        return agentSkillsFormat
                ? parseAgentSkill(source, root, parsed)
                : parseLegacySkill(source, root, parsed);
    }

    private static SkillDocument parseAgentSkill(
            SkillSource source, String root, ParsedFrontmatter parsed) {
        Set<String> unknown = new LinkedHashSet<>(parsed.keys());
        unknown.removeAll(AGENT_SKILL_FIELDS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unsupported Skill frontmatter fields: " + unknown);
        }
        String name = parsed.requiredScalar("name");
        if (!source.directoryName().equals(name)) {
            throw new IllegalArgumentException(
                    "Skill directory must match name: " + source.directoryName() + " != " + name);
        }
        Map<String, String> attributes = parsed.stringMap("metadata");
        Set<String> requiredMods = splitDependencies(attributes.getOrDefault(REQUIRED_MODS_ATTRIBUTE, ""));
        Set<String> allowedTools = splitDependencies(parsed.optionalScalar("allowed-tools").orElse(""));
        Map<String, String> references = discoveredReferences(source, root);
        SkillMetadata metadata = new SkillMetadata(
                name,
                parsed.requiredScalar("description"),
                parsed.optionalScalar("license"),
                parsed.optionalScalar("compatibility"),
                attributes,
                requiredMods,
                allowedTools,
                List.copyOf(references.keySet()),
                source.provenance() + ":" + source.entryPath(),
                source.origin());
        return new SkillDocument(metadata, parsed.body(), references);
    }

    /** Compatibility for pre-Phase-4 in-memory callers; filesystem packages never use this form. */
    private static SkillDocument parseLegacySkill(
            SkillSource source, String root, ParsedFrontmatter parsed) {
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
                parsed.requiredScalar("name"),
                parsed.requiredScalar("description"),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Set.copyOf(parsed.list("required-mods")),
                Set.copyOf(parsed.list("allowed-tools")),
                referencePaths,
                source.provenance() + ":" + source.entryPath(),
                source.origin());
        return new SkillDocument(metadata, parsed.body(), references);
    }

    private static void validateFiles(SkillSource source, String root, boolean agentSkillsFormat) {
        for (String rawPath : source.files().keySet()) {
            String path = SkillSource.normalize(rawPath);
            if (!path.startsWith(root)) {
                throw new IllegalArgumentException("Skill file escapes its package root: " + rawPath);
            }
            String relative = path.substring(root.length());
            String lower = relative.toLowerCase(Locale.ROOT);
            if (lower.startsWith("scripts/") || lower.equals("scripts")) {
                throw new IllegalArgumentException("Skill scripts are not supported: " + rawPath);
            }
            if (agentSkillsFormat
                    && !relative.equals("SKILL.md")
                    && !relative.startsWith("references/")
                    && !relative.startsWith("assets/")) {
                throw new IllegalArgumentException("Unsupported Skill file: " + rawPath);
            }
        }
    }

    private static Map<String, String> discoveredReferences(SkillSource source, String root) {
        Map<String, String> references = new TreeMap<>();
        String prefix = root + "references/";
        source.files().forEach((path, contents) -> {
            if (path.startsWith(prefix)) {
                references.put(path.substring(root.length()), contents);
            }
        });
        return Map.copyOf(references);
    }

    private static Set<String> splitDependencies(String value) {
        LinkedHashSet<String> dependencies = new LinkedHashSet<>();
        for (String item : value.split("[,\\s]+")) {
            if (!item.isBlank()) {
                dependencies.add(item);
            }
        }
        return Set.copyOf(dependencies);
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
        Map<String, Map<String, String>> stringMaps = new LinkedHashMap<>();
        String activeCollection = null;
        CollectionKind collectionKind = null;
        for (String raw : normalized.substring(4, end).split("\n", -1)) {
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) {
                continue;
            }
            if (Character.isWhitespace(raw.charAt(0))) {
                if (activeCollection == null) {
                    throw new IllegalArgumentException("Indented frontmatter value has no key");
                }
                String stripped = raw.stripLeading();
                if (collectionKind == CollectionKind.LIST && stripped.startsWith("- ")) {
                    lists.get(activeCollection).add(unquote(stripped.substring(2).trim()));
                    continue;
                }
                if (collectionKind == CollectionKind.STRING_MAP) {
                    int separator = stripped.indexOf(':');
                    if (separator <= 0) {
                        throw new IllegalArgumentException("Invalid metadata entry: " + raw);
                    }
                    String key = stripped.substring(0, separator).trim();
                    String rawValue = stripped.substring(separator + 1).trim();
                    if (key.isBlank() || rawValue.isBlank()) {
                        throw new IllegalArgumentException("Skill metadata keys and values must be strings");
                    }
                    String previous = stringMaps.get(activeCollection)
                            .put(key, metadataString(rawValue));
                    if (previous != null) {
                        throw new IllegalArgumentException("Duplicate Skill metadata key: " + key);
                    }
                    continue;
                }
                throw new IllegalArgumentException("Invalid frontmatter collection item: " + raw);
            }
            int separator = raw.indexOf(':');
            if (separator <= 0) {
                throw new IllegalArgumentException("Invalid frontmatter line: " + raw);
            }
            String key = raw.substring(0, separator).trim();
            if (scalars.containsKey(key) || lists.containsKey(key) || stringMaps.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate Skill frontmatter field: " + key);
            }
            String content = raw.substring(separator + 1).trim();
            activeCollection = null;
            collectionKind = null;
            if (content.isEmpty()) {
                activeCollection = key;
                if (key.equals("metadata")) {
                    collectionKind = CollectionKind.STRING_MAP;
                    stringMaps.put(key, new LinkedHashMap<>());
                } else {
                    collectionKind = CollectionKind.LIST;
                    lists.put(key, new ArrayList<>());
                }
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
        String body = normalized.substring(end + 5).strip();
        if (body.isBlank()) {
            throw new IllegalArgumentException("Skill instructions must not be blank");
        }
        return new ParsedFrontmatter(scalars, lists, stringMaps, body);
    }

    private static String metadataString(String raw) {
        boolean quoted = raw.length() >= 2
                && ((raw.startsWith("\"") && raw.endsWith("\""))
                        || (raw.startsWith("'") && raw.endsWith("'")));
        if (!quoted) {
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.matches("(?:true|false|null|~|[-+]?\\d+(?:\\.\\d+)?)")
                    || raw.startsWith("[")
                    || raw.startsWith("{")) {
                throw new IllegalArgumentException("Skill metadata values must be strings");
            }
        }
        return unquote(raw);
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
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("file:");
    }

    private enum CollectionKind {
        LIST,
        STRING_MAP
    }

    private record ParsedFrontmatter(
            Map<String, String> scalars,
            Map<String, List<String>> lists,
            Map<String, Map<String, String>> stringMaps,
            String body) {
        private Set<String> keys() {
            LinkedHashSet<String> keys = new LinkedHashSet<>(scalars.keySet());
            keys.addAll(lists.keySet());
            keys.addAll(stringMaps.keySet());
            return Set.copyOf(keys);
        }

        private String requiredScalar(String key) {
            return optionalScalar(key).orElseThrow(
                    () -> new IllegalArgumentException("Missing Skill frontmatter field: " + key));
        }

        private Optional<String> optionalScalar(String key) {
            if (lists.containsKey(key) || stringMaps.containsKey(key)) {
                throw new IllegalArgumentException("Skill frontmatter field must be a string: " + key);
            }
            return Optional.ofNullable(scalars.get(key));
        }

        private List<String> list(String key) {
            if (scalars.containsKey(key) || stringMaps.containsKey(key)) {
                throw new IllegalArgumentException("Skill frontmatter field must be a list: " + key);
            }
            return List.copyOf(lists.getOrDefault(key, List.of()));
        }

        private Map<String, String> stringMap(String key) {
            if (scalars.containsKey(key) || lists.containsKey(key)) {
                throw new IllegalArgumentException("Skill frontmatter field must be a string map: " + key);
            }
            return Map.copyOf(stringMaps.getOrDefault(key, Map.of()));
        }
    }
}
