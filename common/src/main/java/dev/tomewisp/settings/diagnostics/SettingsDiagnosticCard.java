package dev.tomewisp.settings.diagnostics;

import java.util.List;
import java.util.Objects;

/** Friendly diagnostics shape; technical identities are intentionally unrepresentable. */
public record SettingsDiagnosticCard(
        Domain domain,
        FriendlyStatus friendlyStatus,
        String titleKey,
        String statusKey,
        List<String> noteKeys,
        List<Metric> metrics) {
    public enum Domain {
        MODELS,
        KNOWLEDGE,
        HISTORY,
        CONTEXT
    }

    public enum FriendlyStatus {
        READY,
        WORKING,
        ATTENTION,
        UNAVAILABLE,
        NOT_CONNECTED
    }

    public SettingsDiagnosticCard {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(friendlyStatus, "friendlyStatus");
        titleKey = key(titleKey, "titleKey");
        statusKey = key(statusKey, "statusKey");
        noteKeys = noteKeys.stream().map(value -> key(value, "noteKey")).toList();
        metrics = List.copyOf(metrics);
    }

    public record Metric(String labelKey, long value) {
        public Metric {
            labelKey = key(labelKey, "labelKey");
            if (value < 0) {
                throw new IllegalArgumentException("diagnostic metric must not be negative");
            }
        }
    }

    private static String key(String value, String name) {
        if (value == null || !value.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException(name + " must be a localization key");
        }
        return value;
    }
}
