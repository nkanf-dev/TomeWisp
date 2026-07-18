package dev.tomewisp.client.gui.settings;

import dev.tomewisp.settings.skill.SkillSettingsView;
import dev.tomewisp.skill.SkillSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Readable Skill catalog projection with editing limited to explicit local overrides. */
public record SkillSettingsProjection(
        List<Skill> skills,
        int diagnosticCount,
        boolean debugMode) {
    public SkillSettingsProjection {
        skills = List.copyOf(skills);
        if (diagnosticCount < 0) {
            throw new IllegalArgumentException("diagnosticCount must not be negative");
        }
    }

    public static SkillSettingsProjection from(SkillSettingsView view, boolean debugMode) {
        Objects.requireNonNull(view, "view");
        return new SkillSettingsProjection(
                view.skills().stream().map(Skill::from).toList(),
                view.diagnostics().size(),
                debugMode);
    }

    public Optional<Skill> find(String name) {
        return skills.stream().filter(skill -> skill.name().equals(name)).findFirst();
    }

    public record Skill(
            String name,
            String description,
            String body,
            String markdown,
            SkillSource.Origin origin,
            boolean createsOverrideOnSave,
            boolean canDeleteOverride,
            String provenance) {
        static Skill from(SkillSettingsView.Skill skill) {
            return new Skill(
                    skill.metadata().name(),
                    skill.metadata().description(),
                    skill.body(),
                    skill.markdown(),
                    skill.origin(),
                    skill.createsOverrideOnSave(),
                    skill.canDeleteOverride(),
                    skill.metadata().provenance());
        }

        public boolean localOverride() {
            return origin == SkillSource.Origin.LOCAL;
        }
    }
}
