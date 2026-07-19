package dev.openallay.guide.ui;

import dev.openallay.guide.GuideClientModelProfile;
import dev.openallay.guide.GuideModelMode;
import dev.openallay.guide.GuideModelSelection;
import dev.openallay.guide.GuideRequestSnapshot;
import dev.openallay.guide.GuideRequestStatus;
import dev.openallay.guide.GuideSessionSnapshot;
import dev.openallay.guide.GuideSnapshot;
import dev.openallay.guide.GuideTimelineEntry;
import java.util.ArrayList;
import java.util.List;

/** Pure, immutable screen view derived from the one GuideService snapshot. */
public record GuideUiView(
        String selectedSession,
        GuideModelMode modelMode,
        boolean clientModelAvailable,
        boolean serverModelAvailable,
        boolean canSend,
        boolean canCancel,
        boolean canRetry,
        GuideUiProgress progress,
        List<GuideUiSession> sessions,
        List<GuideUiRow> rows,
        List<GuideUiModelChoice> modelChoices,
        String capabilityMessage) {
    public GuideUiView {
        sessions = List.copyOf(sessions);
        rows = List.copyOf(rows);
        modelChoices = List.copyOf(modelChoices);
        if (modelChoices.stream().filter(GuideUiModelChoice::selected).count() != 1) {
            throw new IllegalArgumentException("exactly one model choice must be selected");
        }
        if (modelChoices.stream().filter(GuideUiModelChoice::running).count() > 1) {
            throw new IllegalArgumentException("at most one model choice may be running");
        }
    }

    public GuideUiModelChoice selectedModel() {
        return modelChoices.stream().filter(GuideUiModelChoice::selected)
                .findFirst().orElseThrow();
    }

    public GuideUiModelChoice runningModel() {
        return modelChoices.stream().filter(GuideUiModelChoice::running)
                .findFirst().orElseGet(this::selectedModel);
    }

    public boolean modelSwitchPending() {
        return modelChoices.stream().anyMatch(GuideUiModelChoice::running)
                && !runningModel().selection().equals(selectedModel().selection());
    }

    public static GuideUiView from(GuideSnapshot snapshot) {
        return from(snapshot, GuideDisplayConfig.defaults());
    }

    public static GuideUiView from(GuideSnapshot snapshot, GuideDisplayConfig displayConfig) {
        java.util.Objects.requireNonNull(displayConfig, "displayConfig");
        GuideSessionSnapshot selected = snapshot.sessions().stream()
                .filter(value -> value.sessionId().equals(snapshot.selectedSession()))
                .findFirst().orElseThrow();
        GuideRequestSnapshot active = selected.requests().stream()
                .filter(value -> !value.terminal()).reduce((first, second) -> second).orElse(null);
        GuideRequestSnapshot retry = selected.requests().stream()
                .filter(value -> value.status() == GuideRequestStatus.FAILED
                        || value.status() == GuideRequestStatus.CANCELLED
                        || value.status() == GuideRequestStatus.INTERRUPTED)
                .reduce((first, second) -> second).orElse(null);
        List<GuideUiModelChoice> modelChoices = modelChoices(snapshot, active);
        GuideUiModelChoice selectedModel = modelChoices.stream()
                .filter(GuideUiModelChoice::selected)
                .findFirst().orElseThrow();
        boolean targetAvailable = selectedModel.available();
        List<GuideUiSession> sessions = snapshot.sessions().stream()
                .map(value -> new GuideUiSession(
                        value.sessionId(),
                        value.sessionId().equals(snapshot.selectedSession()),
                        value.requests().stream().anyMatch(request -> !request.terminal()),
                        Math.toIntExact(Math.min(
                                Integer.MAX_VALUE, value.historyWindow().totalRequests()))))
                .toList();
        List<GuideUiRow> rows = new ArrayList<>();
        switch (snapshot.persistence().state()) {
            case LOADING -> rows.add(new GuideUiRow.Persistence(
                    snapshot.persistence().state(),
                    "screen.openallay.history.loading",
                    null));
            case SAVING -> rows.add(new GuideUiRow.Persistence(
                    snapshot.persistence().state(),
                    "screen.openallay.history.saving",
                    null));
            case UNAVAILABLE -> rows.add(new GuideUiRow.Persistence(
                    snapshot.persistence().state(),
                    "screen.openallay.history.unavailable",
                    snapshot.persistence().failure()));
            case DISABLED, AVAILABLE -> { }
        }
        for (GuideRequestSnapshot request : selected.requests()) {
            rows.add(new GuideUiRow.User(request.requestId(), request.userMessage()));
            for (GuideTimelineEntry entry : request.timeline()) {
                switch (entry) {
                    case GuideTimelineEntry.Assistant assistant -> rows.add(
                            new GuideUiRow.Assistant(
                                    request.requestId(),
                                    assistant.ordinal(),
                                    assistant.text(),
                                    assistant.semantic(),
                                    assistant.streaming(),
                                    assistant.sources()));
                    case GuideTimelineEntry.Tool tool -> rows.add(
                            new GuideUiRow.Tool(
                                    request.requestId(),
                                    tool.ordinal(),
                                    tool.activity(),
                                    GuideToolDetailPresenter.project(
                                            tool.activity(), displayConfig.debugMode())));
                }
            }
            if (request.status() == GuideRequestStatus.FAILED
                    || request.status() == GuideRequestStatus.CANCELLED
                    || request.status() == GuideRequestStatus.INTERRUPTED) {
                rows.add(new GuideUiRow.Status(
                        request.requestId(),
                        request.status(),
                        request.failure() == null ? request.status().name() : request.failure().message(),
                        request.failure()));
            }
        }
        GuideUiModelChoice runningModel = modelChoices.stream()
                .filter(GuideUiModelChoice::running)
                .findFirst().orElse(selectedModel);
        String capability = !targetAvailable
                ? "所选模型未配置或不可用：" + selectedModel.displayName()
                : !runningModel.selection().equals(selectedModel.selection())
                        ? "正在使用 " + runningModel.displayName()
                                + "；下次请求 " + selectedModel.displayName()
                        : "当前模型 " + selectedModel.displayName();
        return new GuideUiView(
                snapshot.selectedSession(),
                snapshot.modelMode(),
                snapshot.clientModelAvailable(),
                snapshot.serverModelAvailable(),
                active == null
                        && targetAvailable
                        && snapshot.persistence().state()
                                != dev.openallay.guide.GuidePersistenceSnapshot.State.LOADING,
                active != null,
                retry != null && active == null,
                active == null ? null : GuideUiProgress.from(active.progress()),
                sessions,
                rows,
                modelChoices,
                capability);
    }

    private static List<GuideUiModelChoice> modelChoices(
            GuideSnapshot snapshot, GuideRequestSnapshot active) {
        List<ChoiceSeed> seeds = new ArrayList<>();
        for (GuideClientModelProfile profile : snapshot.clientProfiles()) {
            if (profile.enabled()) {
                seeds.add(new ChoiceSeed(
                        GuideModelSelection.client(profile.id()),
                        profile.displayName(),
                        profile.available()));
            }
        }
        boolean compatibilityClientAvailable = snapshot.clientProfiles().isEmpty()
                && snapshot.clientModelAvailable();
        ensureClientChoice(
                seeds,
                snapshot.clientProfiles(),
                snapshot.modelSelection(),
                compatibilityClientAvailable);
        if (active != null) {
            ensureClientChoice(
                    seeds,
                    snapshot.clientProfiles(),
                    active.modelSelection(),
                    compatibilityClientAvailable
                            && active.modelSelection().equals(snapshot.modelSelection()));
        }
        boolean serverRelevant = snapshot.serverModelAvailable()
                || snapshot.modelSelection().kind() == GuideModelSelection.Kind.SERVER
                || active != null
                        && active.modelSelection().kind() == GuideModelSelection.Kind.SERVER;
        if (serverRelevant) {
            seeds.add(new ChoiceSeed(
                    GuideModelSelection.server(), "Server model", snapshot.serverModelAvailable()));
        }
        GuideModelSelection running = active == null ? null : active.modelSelection();
        return seeds.stream().map(seed -> new GuideUiModelChoice(
                seed.selection(),
                seed.displayName(),
                seed.available(),
                seed.selection().equals(snapshot.modelSelection()),
                seed.selection().equals(running))).toList();
    }

    private static void ensureClientChoice(
            List<ChoiceSeed> seeds,
            List<GuideClientModelProfile> profiles,
            GuideModelSelection selection,
            boolean compatibilityAvailable) {
        if (selection.kind() != GuideModelSelection.Kind.CLIENT
                || seeds.stream().anyMatch(seed -> seed.selection().equals(selection))) {
            return;
        }
        GuideClientModelProfile retained = profiles.stream()
                .filter(profile -> profile.id().equals(selection.profileId()))
                .findFirst().orElse(null);
        seeds.add(new ChoiceSeed(
                selection,
                retained == null ? selection.profileId() : retained.displayName(),
                retained == null ? compatibilityAvailable : retained.available()));
    }

    private record ChoiceSeed(
            GuideModelSelection selection, String displayName, boolean available) {}
}
