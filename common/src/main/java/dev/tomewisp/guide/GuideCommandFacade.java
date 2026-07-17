package dev.tomewisp.guide;

import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Loader-neutral command behavior and notices. */
public final class GuideCommandFacade {
    private final TomeWispRuntime runtime;
    private final GuideServiceManager services;
    private final GuideContextProvider contexts;
    private final GuideScreenOpener screens;

    public GuideCommandFacade(
            TomeWispRuntime runtime,
            GuideServiceManager services,
            GuideContextProvider contexts,
            GuideScreenOpener screens) {
        this.runtime = runtime;
        this.services = services;
        this.contexts = contexts;
        this.screens = screens;
    }

    public void open(UUID actor, Consumer<GuideNotice> notices) {
        emit(screens.open(services.forActor(actor)), notices, ignored -> "已打开 TomeWisp");
    }

    public void ask(UUID actor, String question, Consumer<GuideNotice> notices) {
        GuideService service = services.forActor(actor);
        final GuideSubscription[] subscription = new GuideSubscription[1];
        final UUID[] requestId = new UUID[1];
        final int[] seenTools = {0};
        final GuideRequestStatus[] seenStatus = {null};
        subscription[0] = service.subscribe(snapshot -> {
            if (requestId[0] == null) return;
            GuideRequestSnapshot request = find(snapshot, requestId[0]);
            if (request == null) return;
            while (seenTools[0] < request.tools().size()) {
                GuideToolActivity tool = request.tools().get(seenTools[0]++);
                notices.accept(GuideNotice.info("查询 " + tool.toolId()));
            }
            if (request.status() == GuideRequestStatus.RATE_LIMITED
                    && seenStatus[0] != GuideRequestStatus.RATE_LIMITED) {
                notices.accept(GuideNotice.info(
                        "模型限流，约 " + request.retryAfterMillis() + "ms 后重试"));
            }
            if (request.terminal()) {
                if (request.status() == GuideRequestStatus.COMPLETED) {
                    notices.accept(GuideNotice.info(request.assistantText()));
                } else {
                    notices.accept(GuideNotice.error(
                            request.failure().code() + ": " + request.failure().message()));
                }
                subscription[0].close();
            }
            seenStatus[0] = request.status();
        });
        service.ask(question).thenAccept(result -> {
            if (result instanceof ToolResult.Success<UUID> success) {
                requestId[0] = success.value();
                notices.accept(GuideNotice.info(
                        "思索中… 会话: " + service.snapshot().selectedSession()));
                service.refreshCapabilities();
            } else {
                subscription[0].close();
                failure((ToolResult.Failure<UUID>) result, notices);
            }
        });
    }

    public void cancel(UUID actor, Consumer<GuideNotice> notices) {
        services.forActor(actor).cancel().thenAccept(result -> emit(
                result,
                notices,
                cancelled -> cancelled ? "已取消" : "当前会话没有运行中的请求"));
    }

    public void retry(UUID actor, Consumer<GuideNotice> notices) {
        GuideService service = services.forActor(actor);
        GuideRequestSnapshot request = service.snapshot().sessions().stream()
                .filter(value -> value.sessionId().equals(service.snapshot().selectedSession()))
                .flatMap(value -> value.requests().stream())
                .filter(value -> value.status() == GuideRequestStatus.FAILED
                        || value.status() == GuideRequestStatus.CANCELLED)
                .reduce((first, second) -> second)
                .orElse(null);
        if (request == null) {
            notices.accept(GuideNotice.error("retry_unavailable: 当前会话没有可重试请求"));
            return;
        }
        service.retry(request.requestId()).thenAccept(result -> emit(
                result, notices, id -> "已重试；新请求: " + id));
    }

    public void clear(UUID actor, Consumer<GuideNotice> notices) {
        services.forActor(actor).clearSelectedSession().thenAccept(result -> emit(
                result, notices, ignored -> "已清除当前会话"));
    }

    public void select(UUID actor, String session, Consumer<GuideNotice> notices) {
        services.forActor(actor).selectSession(session).thenAccept(result -> emit(
                result, notices, id -> "已切换到会话 " + id));
    }

    public void close(UUID actor, String session, Consumer<GuideNotice> notices) {
        services.forActor(actor).closeSession(session).thenAccept(result -> emit(
                result, notices, closed -> closed ? "已关闭会话 " + session : "会话不存在"));
    }

    public void sessions(UUID actor, Consumer<GuideNotice> notices) {
        GuideSnapshot snapshot = services.forActor(actor).snapshot();
        notices.accept(GuideNotice.info("会话 " + snapshot.sessions().stream()
                .map(GuideSessionSnapshot::sessionId).toList()
                + "；当前 " + snapshot.selectedSession()));
    }

    public void model(UUID actor, GuideModelMode mode, Consumer<GuideNotice> notices) {
        services.forActor(actor).setModelMode(mode).thenAccept(result -> emit(
                result, notices, selected -> "模型模式已切换为 " + selected.name().toLowerCase()));
    }

    public void status(UUID actor, Consumer<GuideNotice> notices) {
        GuideSnapshot snapshot = services.forActor(actor).snapshot();
        notices.accept(GuideNotice.info(
                "客户端模型 " + snapshot.clientModelAvailable()
                        + "；当前会话 " + snapshot.selectedSession()
                        + "；知识文档 " + runtime.knowledge().snapshot().documents().size()
                        + "；服务端模型 " + snapshot.serverModelAvailable()
                        + "；当前模式 " + snapshot.modelMode().name().toLowerCase()));
    }

    public void skills(Consumer<GuideNotice> notices) {
        notices.accept(GuideNotice.info("Skills: " + runtime.skills().metadata().stream()
                .map(value -> value.name()).toList()));
    }

    public void sources(Consumer<GuideNotice> notices) {
        ToolResult<Integer> refreshed = contexts.refreshKnowledge();
        if (refreshed instanceof ToolResult.Failure<Integer> failure) {
            failure(failure, notices);
            return;
        }
        notices.accept(GuideNotice.info("知识来源: " + runtime.knowledge().snapshot().documents().stream()
                .map(value -> value.sourceId()).distinct().sorted().toList()));
    }

    private static GuideRequestSnapshot find(GuideSnapshot snapshot, UUID requestId) {
        return snapshot.sessions().stream()
                .flatMap(value -> value.requests().stream())
                .filter(value -> value.requestId().equals(requestId))
                .findFirst()
                .orElse(null);
    }

    private static <T> void emit(
            ToolResult<T> result,
            Consumer<GuideNotice> notices,
            java.util.function.Function<T, String> success) {
        if (result instanceof ToolResult.Success<T> value) {
            notices.accept(GuideNotice.info(success.apply(value.value())));
        } else {
            failure((ToolResult.Failure<T>) result, notices);
        }
    }

    private static void failure(
            ToolResult.Failure<?> failure, Consumer<GuideNotice> notices) {
        notices.accept(GuideNotice.error(failure.code() + ": " + failure.message()));
    }
}
