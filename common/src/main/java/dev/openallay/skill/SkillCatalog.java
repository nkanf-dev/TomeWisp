package dev.openallay.skill;

import java.util.List;
import java.util.Optional;

/** Read-only Skill view used by prompts and the load_skill Tool. */
public interface SkillCatalog {
    Optional<SkillDocument> find(String name);

    List<SkillMetadata> metadata();

    default String metadataPrompt() {
        StringBuilder prompt = new StringBuilder();
        for (SkillMetadata metadata : metadata()) {
            prompt.append("  <skill>\n")
                    .append("    <name>").append(xml(metadata.name())).append("</name>\n")
                    .append("    <description>").append(xml(metadata.description()))
                    .append("</description>\n")
                    .append("  </skill>\n");
        }
        return prompt.toString().stripTrailing();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
