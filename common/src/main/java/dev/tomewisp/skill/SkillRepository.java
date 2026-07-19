package dev.tomewisp.skill;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class SkillRepository implements SkillCatalog {
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

    /**
     * Reloads trusted bundled Skills and overlays isolated local filesystem packages. A bad local
     * package never removes a bundled or previously validated local document with the same name.
     */
    public synchronized boolean reload(
            Collection<SkillSource> bundledSources,
            FilesystemSkillLoader.LoadResult localSkills,
            Set<String> installedMods) {
        java.util.Objects.requireNonNull(localSkills, "localSkills");
        Map<String, SkillDocument> candidate = new TreeMap<>();
        List<SkillDiagnostic> nextDiagnostics = new ArrayList<>();
        try {
            for (SkillSource source : List.copyOf(bundledSources)) {
                SkillDocument document = validated(source, installedMods, nextDiagnostics);
                if (document == null) {
                    continue;
                }
                if (candidate.put(document.metadata().name(), document) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate bundled Skill name: " + document.metadata().name());
                }
            }
        } catch (RuntimeException failure) {
            String provenance = bundledSources.isEmpty()
                    ? "tomewisp:bundled"
                    : bundledSources.iterator().next().provenance();
            diagnostics = List.of(new SkillDiagnostic(
                    "skill_validation_failed", failure.getMessage(), provenance));
            return false;
        }

        for (SkillSource source : localSkills.sources()) {
            try {
                SkillDocument document = validated(source, installedMods, nextDiagnostics);
                if (document != null) {
                    candidate.put(document.metadata().name(), document);
                }
            } catch (RuntimeException failure) {
                retainLastValidLocal(source.directoryName(), candidate, installedMods);
                nextDiagnostics.add(new SkillDiagnostic(
                        "skill_validation_failed",
                        failure.getMessage(),
                        source.provenance() + ":" + source.entryPath()));
            }
        }
        for (FilesystemSkillLoader.RejectedSkill rejected : localSkills.rejected()) {
            retainLastValidLocal(rejected.skillName(), candidate, installedMods);
            nextDiagnostics.add(rejected.diagnostic());
        }
        skills = Map.copyOf(candidate);
        diagnostics = List.copyOf(nextDiagnostics);
        return true;
    }

    private SkillDocument validated(
            SkillSource source, Set<String> installedMods, List<SkillDiagnostic> nextDiagnostics) {
        SkillDocument document = parser.parse(source);
        if (!installedMods.containsAll(document.metadata().requiredMods())) {
            Set<String> missing = new java.util.TreeSet<>(document.metadata().requiredMods());
            missing.removeAll(installedMods);
            nextDiagnostics.add(new SkillDiagnostic(
                    "required_mod_unavailable",
                    "Skill " + document.metadata().name() + " requires " + missing,
                    document.metadata().provenance()));
            return null;
        }
        if (!availableTools.containsAll(document.metadata().allowedTools())) {
            Set<String> missing = new java.util.TreeSet<>(document.metadata().allowedTools());
            missing.removeAll(availableTools);
            throw new IllegalArgumentException("Skill declares unavailable tools: " + missing);
        }
        return document;
    }

    private void retainLastValidLocal(
            String name, Map<String, SkillDocument> candidate, Set<String> installedMods) {
        SkillDocument previous = skills.get(name);
        if (previous == null
                || previous.metadata().origin() != SkillSource.Origin.LOCAL
                || !installedMods.containsAll(previous.metadata().requiredMods())
                || !availableTools.containsAll(previous.metadata().allowedTools())) {
            return;
        }
        candidate.put(name, previous);
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

    public SkillCatalogSnapshot snapshot(Set<String> disabledSkills) {
        Set<String> disabled = Set.copyOf(disabledSkills);
        Map<String, SkillDocument> captured = new TreeMap<>();
        for (Map.Entry<String, SkillDocument> entry : skills.entrySet()) {
            if (!disabled.contains(entry.getKey())) {
                captured.put(entry.getKey(), entry.getValue());
            }
        }
        return new SkillCatalogSnapshot(captured);
    }
}
