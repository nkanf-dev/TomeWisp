package dev.openallay.guide.e2e;

import dev.openallay.guide.GuideModelMode;
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
        boolean shutdownAfterReport,
        int historySeedRequests) {
    public static final String ENABLED = "openallay.e2e.enabled";

    public GuideClientE2EConfig {
        if (scenario == null || scenario.isBlank()) throw new IllegalArgumentException("scenario is required");
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid sessionId");
        }
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question is required");
        java.util.Objects.requireNonNull(modelMode, "modelMode");
        java.util.Objects.requireNonNull(reportPath, "reportPath");
        if (historySeedRequests < 0) {
            throw new IllegalArgumentException("historySeedRequests must not be negative");
        }
    }

    public GuideClientE2EConfig(
            String scenario,
            String sessionId,
            String question,
            GuideModelMode modelMode,
            Path reportPath,
            boolean shutdownAfterReport) {
        this(scenario, sessionId, question, modelMode, reportPath, shutdownAfterReport, 0);
    }

    public static Optional<GuideClientE2EConfig> from(Properties properties) {
        if (!Boolean.parseBoolean(properties.getProperty(ENABLED, "false"))) {
            return Optional.empty();
        }
        String mode = properties.getProperty("openallay.e2e.modelMode", "client")
                .toUpperCase(Locale.ROOT);
        return Optional.of(new GuideClientE2EConfig(
                properties.getProperty("openallay.e2e.scenario", "real-client-guide"),
                properties.getProperty("openallay.e2e.session", "e2e"),
                required(properties, "openallay.e2e.question"),
                GuideModelMode.valueOf(mode),
                Path.of(required(properties, "openallay.e2e.report")),
                Boolean.parseBoolean(properties.getProperty("openallay.e2e.shutdown", "true")),
                nonNegativeInteger(properties, "openallay.e2e.historySeedRequests", 0)));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static int nonNegativeInteger(Properties properties, String key, int fallback) {
        int value = Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
        if (value < 0) throw new IllegalArgumentException(key + " must not be negative");
        return value;
    }
}
