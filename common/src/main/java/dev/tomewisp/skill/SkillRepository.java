package dev.tomewisp.skill;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class SkillRepository {
    private final SkillParser parser;
    private final Set<String> availableTools;
    private volatile Map<String, SkillDocument> skills = Map.of();
    private volatile List<SkillDiagnostic> diagnostics = List.of();

    public SkillRepository(SkillParser parser, Collection<String> availableTools) {
        this.parser = parser;
        this.availableTools = Set.copyOf(availableTools);
    }

    public synchronized boolean reload(Collection<SkillSource> sources, Set<String> installedMods) {
        Map<String, SkillDocument> candidate = new TreeMap<>();
        List<SkillDiagnostic> nextDiagnostics = new ArrayList<>();
        try {
            for (SkillSource source : List.copyOf(sources)) {
                SkillDocument document = parser.parse(source);
                if (!installedMods.containsAll(document.metadata().requiredMods())) {
                    Set<String> missing = new java.util.TreeSet<>(document.metadata().requiredMods());
                    missing.removeAll(installedMods);
                    nextDiagnostics.add(new SkillDiagnostic(
                            "required_mod_unavailable",
                            "Skill " + document.metadata().name() + " requires " + missing,
                            document.metadata().provenance()));
                    continue;
                }
                if (!availableTools.containsAll(document.metadata().allowedTools())) {
                    Set<String> missing = new java.util.TreeSet<>(document.metadata().allowedTools());
                    missing.removeAll(availableTools);
                    throw new IllegalArgumentException("Skill declares unavailable tools: " + missing);
                }
                SkillDocument previous = candidate.put(document.metadata().name(), document);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "Duplicate Skill name: " + document.metadata().name());
                }
            }
            skills = Map.copyOf(candidate);
            diagnostics = List.copyOf(nextDiagnostics);
            return true;
        } catch (RuntimeException failure) {
            String provenance = sources.isEmpty() ? "skill-reload" : sources.iterator().next().provenance();
            diagnostics = List.of(new SkillDiagnostic(
                    "skill_validation_failed", failure.getMessage(), provenance));
            return false;
        }
    }

    public Optional<SkillDocument> find(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public List<SkillMetadata> metadata() {
        return skills.values().stream().map(SkillDocument::metadata).toList();
    }

    public List<SkillDiagnostic> diagnostics() {
        return diagnostics;
    }

    public String metadataPrompt() {
        StringBuilder prompt = new StringBuilder("Available Skills (load only when relevant):\n");
        for (SkillMetadata metadata : metadata()) {
            prompt.append("- ").append(metadata.name()).append(": ")
                    .append(metadata.description()).append('\n');
        }
        return prompt.toString();
    }
}
