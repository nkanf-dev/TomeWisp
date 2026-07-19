package dev.tomewisp.skill;

import java.util.List;
import java.util.Optional;

/** Read-only Skill view used by prompts and the load_skill Tool. */
public interface SkillCatalog {
    Optional<SkillDocument> find(String name);

    List<SkillMetadata> metadata();

    default String metadataPrompt() {
        StringBuilder prompt = new StringBuilder("Available Skills (load only when relevant):\n");
        for (SkillMetadata metadata : metadata()) {
            prompt.append("- ").append(metadata.name()).append(": ")
                    .append(metadata.description()).append('\n');
        }
        return prompt.toString();
    }
}
