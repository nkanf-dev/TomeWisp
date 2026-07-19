package dev.openallay.settings;

import dev.openallay.guide.GuideHistorySettingsSnapshot;
import dev.openallay.guide.GuideService;
import dev.openallay.guide.GuideServiceManager;
import dev.openallay.settings.diagnostics.SettingsDiagnosticsAggregator;
import dev.openallay.tool.ToolResult;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** One-time late binding that resolves the current Guide actor at action time. */
public final class ClientSettingsHistoryBinding
        implements ClientSettingsService.HistoryActions {
    private final AtomicReference<GuideServiceManager> manager = new AtomicReference<>();

    public void bind(GuideServiceManager replacement) {
        Objects.requireNonNull(replacement, "replacement");
        GuideServiceManager current = manager.get();
        if (current == replacement) {
            return;
        }
        if (!manager.compareAndSet(null, replacement)) {
            throw new IllegalStateException("settings history binding is already configured");
        }
    }

    @Override
    public ClientSettingsService.HistoryRuntimeState state() {
        GuideServiceManager current = manager.get();
        if (current == null) {
            return ClientSettingsService.HistoryRuntimeState.disconnected();
        }
        GuideHistorySettingsSnapshot snapshot = current.historySettingsSnapshot();
        if (snapshot.guide().isEmpty()) {
            return new ClientSettingsService.HistoryRuntimeState(
                    snapshot.configured(),
                    java.util.Optional.empty(),
                    snapshot.activity(),
                    SettingsDiagnosticsAggregator.HistoryScopeKind.NONE);
        }
        return new ClientSettingsService.HistoryRuntimeState(
                true,
                snapshot.guide(),
                snapshot.activity(),
                switch (snapshot.scopeKind().orElseThrow()) {
                    case SINGLEPLAYER ->
                            SettingsDiagnosticsAggregator.HistoryScopeKind.SINGLEPLAYER_WORLD;
                    case MULTIPLAYER ->
                            SettingsDiagnosticsAggregator.HistoryScopeKind.MULTIPLAYER_SERVER;
                });
    }

    @Override
    public CompletableFuture<ToolResult<Boolean>> deleteCurrentHistory() {
        GuideService service = service();
        return service == null ? unavailable() : service.deleteCurrentHistory();
    }

    @Override
    public CompletableFuture<ToolResult<Boolean>> deleteActorHistory() {
        GuideService service = service();
        return service == null ? unavailable() : service.deleteActorHistory();
    }

    @Override
    public CompletableFuture<ToolResult<Boolean>> resetHistoryDatabase() {
        GuideServiceManager current = manager.get();
        return current == null ? unavailable() : current.resetHistoryDatabase();
    }

    private GuideService service() {
        GuideServiceManager current = manager.get();
        return current == null ? null : current.current();
    }

    private static CompletableFuture<ToolResult<Boolean>> unavailable() {
        return CompletableFuture.completedFuture(new ToolResult.Failure<>(
                "history_unavailable", "Durable Guide history is unavailable"));
    }
}
