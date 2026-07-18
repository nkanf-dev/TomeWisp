package dev.tomewisp.skill;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable Skill documents captured independently from future repository reloads. */
public final class SkillCatalogSnapshot implements SkillCatalog {
    private final Map<String, SkillDocument> skills;

    SkillCatalogSnapshot(Map<String, SkillDocument> skills) {
        TreeMap<String, SkillDocument> canonical = new TreeMap<>(skills);
        canonical.forEach((name, document) -> {
            if (!name.equals(document.metadata().name())) {
                throw new IllegalArgumentException("Skill key does not match document name");
            }
        });
        this.skills = Collections.unmodifiableMap(canonical);
    }

    @Override
    public Optional<SkillDocument> find(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    @Override
    public List<SkillMetadata> metadata() {
        return skills.values().stream().map(SkillDocument::metadata).toList();
    }
}
