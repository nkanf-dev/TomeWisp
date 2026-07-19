package dev.tomewisp.guide.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SemanticDependencyPackagingTest {
    @Test
    void bothLoadersBundleTheSamePinnedHistoryAndSemanticModules() throws Exception {
        Path root = repositoryRoot();
        String properties = Files.readString(root.resolve("gradle.properties"));
        assertTrue(properties.contains("commonmark_version=0.28.0"));
        for (Path build : List.of(root.resolve("fabric/build.gradle"),
                root.resolve("neoforge/build.gradle"))) {
            String source = Files.readString(build);
            int expected = build.toString().contains("neoforge") ? 2 : 1;
            assertEquals(expected,
                    occurrences(source, "org.commonmark:commonmark:${commonmark_version}"));
            assertEquals(expected, occurrences(
                    source,
                    "org.commonmark:commonmark-ext-gfm-tables:${commonmark_version}"));
            assertEquals(expected, occurrences(
                    source, "org.xerial:sqlite-jdbc:${sqlite_jdbc_version}"));
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("common"))) {
            return current;
        }
        if (current.getFileName() != null && current.getFileName().toString().equals("common")) {
            return current.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root");
    }

    private static int occurrences(String source, String value) {
        return source.split(java.util.regex.Pattern.quote(value), -1).length - 1;
    }
}
