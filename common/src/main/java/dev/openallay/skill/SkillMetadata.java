package dev.openallay.skill;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record SkillMetadata(
        String name,
        String description,
        Optional<String> license,
        Optional<String> compatibility,
        Map<String, String> attributes,
        Set<String> requiredMods,
        Set<String> allowedTools,
        List<String> references,
        String provenance,
        SkillSource.Origin origin) {
    public SkillMetadata {
        if (name == null
                || name.length() > 64
                || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("Invalid Skill name: " + name);
        }
        if (description == null || description.isBlank() || description.length() > 1024) {
            throw new IllegalArgumentException("Skill description must not be blank");
        }
        license = java.util.Objects.requireNonNull(license, "license");
        compatibility = java.util.Objects.requireNonNull(compatibility, "compatibility");
        license.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("Skill license must not be blank");
            }
        });
        compatibility.ifPresent(value -> {
            if (value.isBlank() || value.length() > 500) {
                throw new IllegalArgumentException("Invalid Skill compatibility");
            }
        });
        attributes = Map.copyOf(attributes);
        requiredMods = Set.copyOf(requiredMods);
        allowedTools = Set.copyOf(allowedTools);
        references = List.copyOf(references);
        if (provenance == null || provenance.isBlank()) {
            throw new IllegalArgumentException("Skill provenance must not be blank");
        }
        origin = java.util.Objects.requireNonNull(origin, "origin");
    }
}
