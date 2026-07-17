package dev.tomewisp.guide.e2e;

import dev.tomewisp.guide.GuideModelMode;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/** Explicit, inert-by-default configuration for the real Minecraft client harness. */
public record GuideClientE2EConfig(
        String scenario,
        String sessionId,
        String question,
        GuideModelMode modelMode,
        Path reportPath,
        boolean shutdownAfterReport) {
    public static final String ENABLED = "tomewisp.e2e.enabled";

    public GuideClientE2EConfig {
        if (scenario == null || scenario.isBlank()) throw new IllegalArgumentException("scenario is required");
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid sessionId");
        }
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question is required");
        java.util.Objects.requireNonNull(modelMode, "modelMode");
        java.util.Objects.requireNonNull(reportPath, "reportPath");
    }

    public static Optional<GuideClientE2EConfig> from(Properties properties) {
        if (!Boolean.parseBoolean(properties.getProperty(ENABLED, "false"))) {
            return Optional.empty();
        }
        String mode = properties.getProperty("tomewisp.e2e.modelMode", "client")
                .toUpperCase(Locale.ROOT);
        return Optional.of(new GuideClientE2EConfig(
                properties.getProperty("tomewisp.e2e.scenario", "real-client-guide"),
                properties.getProperty("tomewisp.e2e.session", "e2e"),
                required(properties, "tomewisp.e2e.question"),
                GuideModelMode.valueOf(mode),
                Path.of(required(properties, "tomewisp.e2e.report")),
                Boolean.parseBoolean(properties.getProperty("tomewisp.e2e.shutdown", "true"))));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
}
