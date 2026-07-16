package dev.tomewisp.fabric;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.client.ClientGuideRuntime;
import dev.tomewisp.client.context.ClientContextCapture;
import dev.tomewisp.client.resource.MinecraftClientResourceAccess;
import dev.tomewisp.integration.patchouli.PatchouliKnowledgeProvider;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class FabricGuideCommands {
    private FabricGuideCommands() {}

    public static void register(
            TomeWispRuntime base, ToolResult<ClientGuideRuntime> configured) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                literal("guide")
                        .then(literal("cancel").executes(context -> cancel(configured, context.getSource())))
                        .then(literal("clear").executes(context -> clear(configured, context.getSource())))
                        .then(literal("status").executes(context -> status(base, configured, context.getSource())))
                        .then(literal("skills").executes(context -> skills(base, context.getSource())))
                        .then(literal("sources").executes(context -> sources(base, context.getSource())))
                        .then(literal("session")
                                .then(literal("list").executes(context -> sessions(configured, context.getSource())))
                                .then(literal("new").then(argument("id", word()).executes(context ->
                                        select(configured, context.getSource(), getString(context, "id")))))
                                .then(literal("switch").then(argument("id", word()).executes(context ->
                                        select(configured, context.getSource(), getString(context, "id")))))
                                .then(literal("close").then(argument("id", word()).executes(context ->
                                        close(configured, context.getSource(), getString(context, "id"))))))
                        .then(literal("ask").then(argument("question", greedyString()).executes(context ->
                                ask(base, configured, context.getSource(), getString(context, "question")))))
                        .then(argument("question", greedyString()).executes(context ->
                                ask(base, configured, context.getSource(), getString(context, "question"))))));
    }

    private static int ask(
            TomeWispRuntime base,
            ToolResult<ClientGuideRuntime> configured,
            FabricClientCommandSource source,
            String question) {
        if (!(configured instanceof ToolResult.Success<ClientGuideRuntime> success)) {
            failure(source, (ToolResult.Failure<ClientGuideRuntime>) configured);
            return 0;
        }
        Minecraft client = source.getClient();
        reloadKnowledge(base, client);
        ClientGuideRuntime guide = success.value();
        var context = new ClientContextCapture(new com.google.gson.Gson()).capture(
                client, guide.requiredContext(), java.util.UUID.randomUUID().toString());
        source.sendFeedback(Component.literal("[TomeWisp] 思索中… 会话: "
                + guide.selectedSession(source.getPlayer().getUUID())));
        guide.ask(source.getPlayer().getUUID(), question, context, event -> publish(source, event));
        return 1;
    }

    private static void reloadKnowledge(TomeWispRuntime base, Minecraft client) {
        String locale = client.getLanguageManager().getSelected();
        base.knowledge().reload(List.of(new PatchouliKnowledgeProvider(
                new MinecraftClientResourceAccess(client.getResourceManager()), locale)));
    }

    private static void publish(FabricClientCommandSource source, AgentEvent event) {
        if (event instanceof AgentEvent.FinalText text) {
            source.sendFeedback(Component.literal("[TomeWisp] " + text.text()));
        } else if (event instanceof AgentEvent.Failed failed) {
            source.sendError(Component.literal("[TomeWisp] " + failed.code() + ": " + failed.message()));
        } else if (event instanceof AgentEvent.ToolStarted tool) {
            source.sendFeedback(Component.literal("[TomeWisp] 查询 " + tool.toolId()));
        } else if (event instanceof AgentEvent.ModelProgress progress
                && progress.event() instanceof ModelEvent.RateLimited limited) {
            source.sendFeedback(Component.literal("[TomeWisp] 模型限流，约 "
                    + limited.retryAfterMillis() + "ms 后重试（第 " + limited.attempt() + " 次）"));
        }
    }

    private static int cancel(ToolResult<ClientGuideRuntime> configured, FabricClientCommandSource source) {
        return with(configured, source, guide -> {
            boolean cancelled = guide.cancel(source.getPlayer().getUUID());
            source.sendFeedback(Component.literal(cancelled ? "[TomeWisp] 已取消" : "[TomeWisp] 当前会话没有运行中的请求"));
        });
    }

    private static int clear(ToolResult<ClientGuideRuntime> configured, FabricClientCommandSource source) {
        return with(configured, source, guide -> {
            guide.clearActor(source.getPlayer().getUUID());
            source.sendFeedback(Component.literal("[TomeWisp] 已清除本地会话"));
        });
    }

    private static int sessions(ToolResult<ClientGuideRuntime> configured, FabricClientCommandSource source) {
        return with(configured, source, guide -> source.sendFeedback(Component.literal(
                "[TomeWisp] 会话 " + guide.sessions(source.getPlayer().getUUID())
                        + "；当前 " + guide.selectedSession(source.getPlayer().getUUID()))));
    }

    private static int select(
            ToolResult<ClientGuideRuntime> configured, FabricClientCommandSource source, String id) {
        return with(configured, source, guide -> {
            guide.selectSession(source.getPlayer().getUUID(), id);
            source.sendFeedback(Component.literal("[TomeWisp] 已切换到会话 " + id));
        });
    }

    private static int close(
            ToolResult<ClientGuideRuntime> configured, FabricClientCommandSource source, String id) {
        return with(configured, source, guide -> {
            guide.closeSession(source.getPlayer().getUUID(), id);
            source.sendFeedback(Component.literal("[TomeWisp] 已关闭会话 " + id));
        });
    }

    private static int status(
            TomeWispRuntime base,
            ToolResult<ClientGuideRuntime> configured,
            FabricClientCommandSource source) {
        if (configured instanceof ToolResult.Success<ClientGuideRuntime> success) {
            source.sendFeedback(Component.literal("[TomeWisp] 客户端模型已配置；当前会话 "
                    + success.value().selectedSession(source.getPlayer().getUUID())
                    + "；知识文档 " + base.knowledge().snapshot().documents().size()));
            return 1;
        }
        failure(source, (ToolResult.Failure<ClientGuideRuntime>) configured);
        return 0;
    }

    private static int skills(TomeWispRuntime base, FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("[TomeWisp] Skills: "
                + base.skills().metadata().stream().map(value -> value.name()).toList()));
        return 1;
    }

    private static int sources(TomeWispRuntime base, FabricClientCommandSource source) {
        reloadKnowledge(base, source.getClient());
        source.sendFeedback(Component.literal("[TomeWisp] 知识来源: "
                + base.knowledge().snapshot().documents().stream()
                        .map(value -> value.sourceId()).distinct().sorted().toList()));
        return 1;
    }

    private static int with(
            ToolResult<ClientGuideRuntime> configured,
            FabricClientCommandSource source,
            java.util.function.Consumer<ClientGuideRuntime> action) {
        if (configured instanceof ToolResult.Success<ClientGuideRuntime> success) {
            action.accept(success.value());
            return 1;
        }
        failure(source, (ToolResult.Failure<ClientGuideRuntime>) configured);
        return 0;
    }

    private static void failure(
            FabricClientCommandSource source, ToolResult.Failure<ClientGuideRuntime> failure) {
        source.sendError(Component.literal("[TomeWisp] " + failure.code() + ": " + failure.message()));
    }
}
