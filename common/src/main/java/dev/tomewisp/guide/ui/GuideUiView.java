package dev.tomewisp.guide.ui;

import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuideRequestSnapshot;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideSessionSnapshot;
import dev.tomewisp.guide.GuideSnapshot;
import dev.tomewisp.guide.GuideTimelineEntry;
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
        List<GuideUiSession> sessions,
        List<GuideUiRow> rows,
        String capabilityMessage) {
    public GuideUiView {
        sessions = List.copyOf(sessions);
        rows = List.copyOf(rows);
    }

    public static GuideUiView from(GuideSnapshot snapshot) {
        GuideSessionSnapshot selected = snapshot.sessions().stream()
                .filter(value -> value.sessionId().equals(snapshot.selectedSession()))
                .findFirst().orElseThrow();
        GuideRequestSnapshot active = selected.requests().stream()
                .filter(value -> !value.terminal()).reduce((first, second) -> second).orElse(null);
        GuideRequestSnapshot retry = selected.requests().stream()
                .filter(value -> value.status() == GuideRequestStatus.FAILED
                        || value.status() == GuideRequestStatus.CANCELLED)
                .reduce((first, second) -> second).orElse(null);
        boolean targetAvailable = snapshot.modelMode() == GuideModelMode.CLIENT
                ? snapshot.clientModelAvailable()
                : snapshot.serverModelAvailable();
        List<GuideUiSession> sessions = snapshot.sessions().stream()
                .map(value -> new GuideUiSession(
                        value.sessionId(),
                        value.sessionId().equals(snapshot.selectedSession()),
                        value.requests().stream().anyMatch(request -> !request.terminal()),
                        value.requests().size()))
                .toList();
        List<GuideUiRow> rows = new ArrayList<>();
        for (GuideRequestSnapshot request : selected.requests()) {
            rows.add(new GuideUiRow.User(request.requestId(), request.userMessage()));
            for (GuideTimelineEntry entry : request.timeline()) {
                switch (entry) {
                    case GuideTimelineEntry.Assistant assistant -> rows.add(
                            new GuideUiRow.Assistant(
                                    request.requestId(),
                                    assistant.ordinal(),
                                    assistant.text(),
                                    assistant.streaming(),
                                    assistant.sources()));
                    case GuideTimelineEntry.Tool tool -> rows.add(
                            new GuideUiRow.Tool(
                                    request.requestId(),
                                    tool.ordinal(),
                                    tool.activity()));
                }
            }
            if (request.status() == GuideRequestStatus.RATE_LIMITED) {
                rows.add(new GuideUiRow.Status(
                        request.requestId(),
                        request.status(),
                        "模型限流，等待 " + request.retryAfterMillis() + "ms",
                        null));
            } else if (request.status() == GuideRequestStatus.FAILED
                    || request.status() == GuideRequestStatus.CANCELLED) {
                rows.add(new GuideUiRow.Status(
                        request.requestId(),
                        request.status(),
                        request.failure() == null ? request.status().name() : request.failure().message(),
                        request.failure()));
            }
        }
        String capability = targetAvailable
                ? (snapshot.modelMode() == GuideModelMode.CLIENT ? "客户端模型" : "服务端模型")
                : (snapshot.modelMode() == GuideModelMode.CLIENT
                        ? "客户端模型未配置；可配置 model.json 或选择可用的服务端模型"
                        : "当前服务器未提供模型；请选择客户端模型");
        return new GuideUiView(
                snapshot.selectedSession(),
                snapshot.modelMode(),
                snapshot.clientModelAvailable(),
                snapshot.serverModelAvailable(),
                active == null && targetAvailable,
                active != null,
                retry != null && active == null,
                sessions,
                rows,
                capability);
    }
}
