package dev.tomewisp.context.game;

import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.PlayerSnapshot;
import dev.tomewisp.platform.InstalledModMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One detached snapshot of state a player can already see in menus, HUD/F3, or their own UI.
 * It intentionally has no spatial-search, external-container, command-string, or write surface.
 */
public record ObservableGameStateSnapshot(
        Instant capturedAt,
        RuntimeState runtime,
        ModsState mods,
        OptionsState options,
        PacksState packs,
        ShaderState shaders,
        DiagnosticsState diagnostics,
        PlayerUiState player,
        WorldQueriesState worldQueries) {
    public ObservableGameStateSnapshot {
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(mods, "mods");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(packs, "packs");
        Objects.requireNonNull(shaders, "shaders");
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(worldQueries, "worldQueries");
    }

    public record SectionDiagnostic(String code, String message) {
        public SectionDiagnostic {
            code = require(code, "code");
            message = require(message, "message");
        }
    }

    public record RuntimeState(
            String gameVersion,
            String loader,
            boolean developmentEnvironment,
            String connectionKind,
            EvidenceMetadata evidence,
            List<SectionDiagnostic> diagnostics) {
        public RuntimeState {
            gameVersion = require(gameVersion, "gameVersion");
            loader = require(loader, "loader");
            connectionKind = require(connectionKind, "connectionKind");
            Objects.requireNonNull(evidence, "evidence");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record ModsState(
            List<InstalledModMetadata> installed,
            EvidenceMetadata evidence,
            List<SectionDiagnostic> diagnostics) {
        public ModsState {
            installed = List.copyOf(installed);
            Objects.requireNonNull(evidence, "evidence");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record OptionValue(String group, String key, String displayName, String value) {
        public OptionValue {
            group = require(group, "group");
            key = require(key, "key");
            displayName = require(displayName, "displayName");
            value = Objects.requireNonNull(value, "value");
        }
    }

    public record OptionsState(
            List<OptionValue> values,
            EvidenceMetadata evidence,
            List<SectionDiagnostic> diagnostics) {
        public OptionsState {
            values = List.copyOf(values);
            Objects.requireNonNull(evidence, "evidence");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record PackInfo(
            String id,
            String title,
            String description,
            boolean selected,
            boolean required,
            String compatibility,
            String source) {
        public PackInfo {
            id = require(id, "id");
            title = require(title, "title");
            description = description == null ? "" : description;
            compatibility = require(compatibility, "compatibility");
            source = require(source, "source");
        }
    }

    public record PacksState(
            List<PackInfo> resourcePacks,
            List<String> visibleDataPacks,
            EvidenceMetadata evidence,
            List<SectionDiagnostic> diagnostics) {
        public PacksState {
            resourcePacks = List.copyOf(resourcePacks);
            visibleDataPacks = List.copyOf(visibleDataPacks);
            Objects.requireNonNull(evidence, "evidence");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record ShaderState(
            boolean integrationAvailable,
            String provider,
            String selectedPack,
            Map<String, String> publicOptions,
            EvidenceMetadata evidence,
            List<SectionDiagnostic> diagnostics) {
        public ShaderState {
            provider = require(provider, "provider");
            selectedPack = selectedPack == null ? "" : selectedPack;
            publicOptions = Map.copyOf(publicOptions);
            Objects.requireNonNull(evidence, "evidence");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record DiagnosticValue(String category, String key, String value) {
        public DiagnosticValue {
            category = require(category, "category");
            key = require(key, "key");
            value = Objects.requireNonNull(value, "value");
        }
    }

    public record DiagnosticsState(
            List<DiagnosticValue> values,
            EvidenceMetadata evidence,
            List<SectionDiagnostic> diagnostics) {
        public DiagnosticsState {
            values = List.copyOf(values);
            Objects.requireNonNull(evidence, "evidence");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record PlayerUiState(
            PlayerSnapshot player,
            String openScreen,
            String openScreenTitle,
            EvidenceMetadata evidence,
            List<SectionDiagnostic> diagnostics) {
        public PlayerUiState {
            openScreen = require(openScreen, "openScreen");
            openScreenTitle = openScreenTitle == null ? "" : openScreenTitle;
            Objects.requireNonNull(evidence, "evidence");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record QueryValue(
            String operation,
            String value,
            boolean authoritative,
            String authorityNote) {
        public QueryValue {
            operation = require(operation, "operation");
            value = Objects.requireNonNull(value, "value");
            authorityNote = require(authorityNote, "authorityNote");
        }
    }

    public record WorldQueriesState(
            Map<String, QueryValue> values,
            EvidenceMetadata evidence,
            List<SectionDiagnostic> diagnostics) {
        public WorldQueriesState {
            values = Map.copyOf(values);
            Objects.requireNonNull(evidence, "evidence");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
