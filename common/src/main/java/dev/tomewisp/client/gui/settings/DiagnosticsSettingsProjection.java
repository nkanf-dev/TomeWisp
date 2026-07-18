package dev.tomewisp.client.gui.settings;

import dev.tomewisp.settings.diagnostics.SettingsDiagnosticCard;
import dev.tomewisp.settings.diagnostics.SettingsDiagnosticsSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Diagnostics page with friendly cards and a distinctly typed Debug Mode section. */
public record DiagnosticsSettingsProjection(
        String titleKey,
        String narrationKey,
        List<CardRow> cards,
        Optional<DebugSection> debug) {
    public DiagnosticsSettingsProjection {
        cards = List.copyOf(cards);
        debug = Objects.requireNonNull(debug, "debug");
    }

    public static DiagnosticsSettingsProjection from(SettingsDiagnosticsSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<CardRow> cards = snapshot.cards().stream()
                .map(card -> new CardRow(
                        card.domain(),
                        card.titleKey(),
                        card.statusKey(),
                        card.statusKey(),
                        icon(card.friendlyStatus()),
                        card.metrics()))
                .toList();
        return new DiagnosticsSettingsProjection(
                "screen.tomewisp.settings.diagnostics.title",
                "screen.tomewisp.settings.diagnostics.narration",
                cards,
                snapshot.debug().map(debug -> new DebugSection(
                        "screen.tomewisp.settings.diagnostics.debug.title",
                        "screen.tomewisp.settings.diagnostics.debug.narration",
                        debug)));
    }

    private static String icon(SettingsDiagnosticCard.FriendlyStatus status) {
        return switch (status) {
            case READY -> "✓";
            case WORKING -> "…";
            case ATTENTION -> "!";
            case UNAVAILABLE -> "×";
            case NOT_CONNECTED -> "○";
        };
    }

    public record CardRow(
            SettingsDiagnosticCard.Domain domain,
            String titleKey,
            String statusKey,
            String statusTextKey,
            String statusIcon,
            List<SettingsDiagnosticCard.Metric> metrics) {
        public CardRow {
            Objects.requireNonNull(domain, "domain");
            requireKey(titleKey, "titleKey");
            requireKey(statusKey, "statusKey");
            requireKey(statusTextKey, "statusTextKey");
            if (statusIcon == null || statusIcon.isBlank()) {
                throw new IllegalArgumentException("statusIcon is required");
            }
            metrics = List.copyOf(metrics);
        }
    }

    public record DebugSection(
            String titleKey,
            String narrationKey,
            SettingsDiagnosticsSnapshot.DebugSettingsDiagnostics diagnostics) {
        public DebugSection {
            requireKey(titleKey, "titleKey");
            requireKey(narrationKey, "narrationKey");
            Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }

    private static void requireKey(String value, String name) {
        if (value == null || !value.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException(name + " must be a localization key");
        }
    }
}
