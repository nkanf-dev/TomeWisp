package dev.openallay.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CommonLoaderIsolationTest {
    private static final List<String> FORBIDDEN =
            List.of("import net.fabricmc.", "import net.neoforged.");

    @Test
    void commonProductionSourcesDoNotImportLoaderApis() throws IOException {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            List<Path> violations = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(CommonLoaderIsolationTest::containsForbiddenImport)
                    .toList();
            assertTrue(
                    violations.isEmpty(),
                    () -> "Common source imports loader APIs: " + violations);
        }
    }

    private static boolean containsForbiddenImport(Path path) {
        try {
            String source = Files.readString(path);
            return FORBIDDEN.stream().anyMatch(source::contains);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
