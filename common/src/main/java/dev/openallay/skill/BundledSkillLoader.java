package dev.openallay.skill;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BundledSkillLoader {
    public static final List<String> NAMES = List.of(
            "analyze-game-data",
            "answer-modded-minecraft-question",
            "explain-machine-usage",
            "diagnose-missing-recipe",
            "guide-ftb-progression",
            "inspect-game-state",
            "search-guide-books");
    private static final Map<String, List<String>> SUPPORT_FILES = Map.of(
            "analyze-game-data", List.of(
                    "references/datasets.md",
                    "references/examples.md",
                    "references/pipelines.md"));

    public List<SkillSource> load() {
        List<SkillSource> sources = new ArrayList<>();
        ClassLoader loader = BundledSkillLoader.class.getClassLoader();
        for (String name : NAMES) {
            String path = "assets/openallay/openallay_skills/" + name + "/SKILL.md";
            try (InputStream input = loader.getResourceAsStream(path)) {
                if (input == null) {
                    throw new IllegalStateException("Missing bundled Skill " + path);
                }
                java.util.LinkedHashMap<String, String> files = new java.util.LinkedHashMap<>();
                files.put(path, new String(input.readAllBytes(), StandardCharsets.UTF_8));
                for (String relative : SUPPORT_FILES.getOrDefault(name, List.of())) {
                    String supportPath = "assets/openallay/openallay_skills/" + name + "/" + relative;
                    try (InputStream support = loader.getResourceAsStream(supportPath)) {
                        if (support == null) {
                            throw new IllegalStateException("Missing bundled Skill support file " + supportPath);
                        }
                        files.put(supportPath, new String(support.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
                sources.add(new SkillSource(
                        "openallay:bundled", path, files, SkillSource.Origin.BUNDLED));
            } catch (IOException failure) {
                throw new UncheckedIOException("Unable to read bundled Skill " + path, failure);
            }
        }
        return List.copyOf(sources);
    }
}
