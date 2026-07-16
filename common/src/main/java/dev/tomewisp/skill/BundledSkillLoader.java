package dev.tomewisp.skill;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BundledSkillLoader {
    public static final List<String> NAMES = List.of(
            "answer-modded-minecraft-question",
            "explain-machine-usage",
            "diagnose-missing-recipe",
            "guide-ftb-progression",
            "search-guide-books");

    public List<SkillSource> load() {
        List<SkillSource> sources = new ArrayList<>();
        ClassLoader loader = BundledSkillLoader.class.getClassLoader();
        for (String name : NAMES) {
            String path = "assets/tomewisp/tomewisp_skills/" + name + "/skill.md";
            try (InputStream input = loader.getResourceAsStream(path)) {
                if (input == null) {
                    throw new IllegalStateException("Missing bundled Skill " + path);
                }
                sources.add(new SkillSource(
                        "tomewisp:bundled", path,
                        Map.of(path, new String(input.readAllBytes(), StandardCharsets.UTF_8))));
            } catch (IOException failure) {
                throw new UncheckedIOException("Unable to read bundled Skill " + path, failure);
            }
        }
        return List.copyOf(sources);
    }
}
