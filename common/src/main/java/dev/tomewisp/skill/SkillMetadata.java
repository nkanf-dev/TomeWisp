package dev.tomewisp.skill;

import java.util.List;
import java.util.Set;

public record SkillMetadata(
        String name,
        String description,
        Set<String> requiredMods,
        Set<String> allowedTools,
        List<String> references,
        String provenance) {
    public SkillMetadata {
        if (name == null || !name.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("Invalid Skill name: " + name);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Skill description must not be blank");
        }
        requiredMods = Set.copyOf(requiredMods);
        allowedTools = Set.copyOf(allowedTools);
        references = List.copyOf(references);
        if (provenance == null || provenance.isBlank()) {
            throw new IllegalArgumentException("Skill provenance must not be blank");
        }
    }
}
