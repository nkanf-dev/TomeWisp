package dev.tomewisp.skill;

import java.util.Map;

public record SkillDocument(
        SkillMetadata metadata, String instructions, Map<String, String> references) {
    public SkillDocument {
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("Skill instructions must not be blank");
        }
        references = Map.copyOf(references);
    }
}
