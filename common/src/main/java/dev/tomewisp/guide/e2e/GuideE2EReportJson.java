package dev.tomewisp.guide.e2e;

import com.google.gson.Gson;
import dev.tomewisp.trace.replay.ToolResultNormalizer;
import java.util.Objects;
import java.util.Set;

public final class GuideE2EReportJson {
    private final Gson gson;

    public GuideE2EReportJson(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public String encode(GuideE2EReport report, Set<String> secrets) {
        String encoded = gson.toJson(new ToolResultNormalizer(gson)
                .canonicalize(gson.toJsonTree(report)));
        for (String secret : Set.copyOf(secrets)) {
            if (secret != null && !secret.isBlank()) {
                encoded = encoded.replace(secret, "[REDACTED]");
            }
        }
        return encoded;
    }
}
