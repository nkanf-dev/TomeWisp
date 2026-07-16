package dev.tomewisp.neoforge;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.client.ClientGuideRuntime;
import dev.tomewisp.client.context.ClientContextCapture;
import dev.tomewisp.client.resource.MinecraftClientResourceAccess;
import dev.tomewisp.integration.patchouli.PatchouliKnowledgeProvider;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import dev.tomewisp.neoforge.network.NeoForgeClientBridge;

public final class NeoForgeGuideCommands {
    private static final java.util.Map<java.util.UUID, String> MODEL_MODES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private NeoForgeGuideCommands() {}

    public static void register(
            TomeWispRuntime base,
            ToolResult<ClientGuideRuntime> configured,
            NeoForgeClientBridge bridge) {
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> event.getDispatcher().register(
                literal("guide")
                        .then(literal("cancel").executes(context -> cancel(configured, context.getSource())))
                        .then(literal("clear").executes(context -> clear(configured, context.getSource())))
                        .then(literal("status").executes(context -> status(
                                base, configured, bridge, context.getSource())))
                        .then(literal("skills").executes(context -> skills(base, context.getSource())))
                        .then(literal("sources").executes(context -> sources(base, context.getSource())))
                        .then(literal("model")
                                .then(literal("client").executes(context -> modelMode(
                                        context.getSource(), "client", bridge)))
                                .then(literal("server").executes(context -> modelMode(
                                        context.getSource(), "server", bridge))))
                        .then(literal("session")
                                .then(literal("list").executes(context -> sessions(configured, context.getSource())))
                                .then(literal("new").then(argument("id", word()).executes(context ->
                                        select(configured, context.getSource(), getString(context, "id")))))
                                .then(literal("switch").then(argument("id", word()).executes(context ->
                                        select(configured, context.getSource(), getString(context, "id")))))
                                .then(literal("close").then(argument("id", word()).executes(context ->
                                        close(configured, context.getSource(), getString(context, "id"))))))
                        .then(literal("ask").then(argument("question", greedyString()).executes(context ->
                                ask(base, configured, bridge, context.getSource(), getString(context, "question")))))
                        .then(argument("question", greedyString()).executes(context ->
                                ask(base, configured, bridge, context.getSource(), getString(context, "question"))))));
    }

    private static int ask(
            TomeWispRuntime base,
            ToolResult<ClientGuideRuntime> configured,
            NeoForgeClientBridge bridge,
            CommandSourceStack source,
            String question) {
        String mode = MODEL_MODES.getOrDefault(actor(), "client");
        if (mode.equals("server")) {
            String session = configured instanceof ToolResult.Success<ClientGuideRuntime> success
                    ? success.value().selectedSession(actor())
                    : "main";
            var request = new dev.tomewisp.bridge.protocol.ServerAgentRequestPayload(
                    dev.tomewisp.bridge.protocol.BridgeProtocol.VERSION,
                    java.util.UUID.randomUUID(), session, question, true);
            if (!bridge.askServer(request, event -> publishServer(source, event))) {
                source.sendFailure(Component.literal("[TomeWisp] 当前服务器没有提供模型能力"));
                return 0;
            }
            message(source, "[TomeWisp] 已提交到服务端模型；会话: " + session);
            return 1;
        }
        if (!(configured instanceof ToolResult.Success<ClientGuideRuntime> success)) {
            failure(source, (ToolResult.Failure<ClientGuideRuntime>) configured);
            return 0;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            source.sendFailure(Component.literal("[TomeWisp] 当前没有客户端玩家"));
            return 0;
        }
        reloadKnowledge(base, client);
        ClientGuideRuntime guide = success.value();
        var context = new ClientContextCapture(new com.google.gson.Gson()).capture(
                client, guide.requiredContext(), java.util.UUID.randomUUID().toString());
        source.sendSuccess(() -> Component.literal("[TomeWisp] 思索中… 会话: "
                + guide.selectedSession(client.player.getUUID())), false);
        guide.ask(client.player.getUUID(), question, context, event -> publish(source, event));
        return 1;
    }

    private static void publishServer(
            CommandSourceStack source,
            dev.tomewisp.bridge.protocol.ServerAgentEventPayload event) {
        try {
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(event.eventJson())
                    .getAsJsonObject();
            if (event.eventType().equals("final_text")) {
                message(source, "[TomeWisp/server] " + json.get("text").getAsString());
            } else if (event.eventType().equals("failed")) {
                source.sendFailure(Component.literal("[TomeWisp/server] "
                        + json.get("code").getAsString() + ": "
                        + json.get("message").getAsString()));
            } else if (event.eventType().equals("tool_started")) {
                message(source, "[TomeWisp/server] 查询 " + json.get("toolId").getAsString());
            }
        } catch (RuntimeException failure) {
            source.sendFailure(Component.literal("[TomeWisp] 无法解析服务端 Agent 事件"));
        }
    }

    private static int modelMode(
            CommandSourceStack source, String mode, NeoForgeClientBridge bridge) {
        if (mode.equals("server") && !bridge.capabilities().serverModel()) {
            source.sendFailure(Component.literal("[TomeWisp] 当前服务器没有提供模型能力"));
            return 0;
        }
        MODEL_MODES.put(actor(), mode);
        message(source, "[TomeWisp] 模型模式已切换为 " + mode);
        return 1;
    }

    private static void reloadKnowledge(TomeWispRuntime base, Minecraft client) {
        java.util.List<dev.tomewisp.knowledge.KnowledgeSourceProvider> providers =
                new java.util.ArrayList<>();
        providers.add(new PatchouliKnowledgeProvider(
                new MinecraftClientResourceAccess(client.getResourceManager()),
                client.getLanguageManager().getSelected()));
        if (base.platform().isModLoaded("ftbquests") && client.player != null) {
            providers.add(new dev.tomewisp.integration.ftb.quests.FtbQuestsKnowledgeProvider(
                    new dev.tomewisp.integration.ftb.quests.ReflectiveFtbQuestsBridge(
                            NeoForgeGuideCommands.class.getClassLoader()),
                    client.player,
                    true));
        }
        base.knowledge().reload(providers);
    }

    private static void publish(CommandSourceStack source, AgentEvent event) {
        if (event instanceof AgentEvent.FinalText text) {
            source.sendSuccess(() -> Component.literal("[TomeWisp] " + text.text()), false);
        } else if (event instanceof AgentEvent.Failed failed) {
            source.sendFailure(Component.literal("[TomeWisp] " + failed.code() + ": " + failed.message()));
        } else if (event instanceof AgentEvent.ToolStarted tool) {
            source.sendSuccess(() -> Component.literal("[TomeWisp] 查询 " + tool.toolId()), false);
        } else if (event instanceof AgentEvent.ModelProgress progress
                && progress.event() instanceof ModelEvent.RateLimited limited) {
            source.sendSuccess(() -> Component.literal("[TomeWisp] 模型限流，约 "
                    + limited.retryAfterMillis() + "ms 后重试（第 " + limited.attempt() + " 次）"), false);
        }
    }

    private static int cancel(ToolResult<ClientGuideRuntime> configured, CommandSourceStack source) {
        return with(configured, source, guide -> {
            boolean cancelled = guide.cancel(actor());
            message(source, cancelled ? "[TomeWisp] 已取消" : "[TomeWisp] 当前会话没有运行中的请求");
        });
    }

    private static int clear(ToolResult<ClientGuideRuntime> configured, CommandSourceStack source) {
        return with(configured, source, guide -> {
            guide.clearActor(actor());
            message(source, "[TomeWisp] 已清除本地会话");
        });
    }

    private static int sessions(ToolResult<ClientGuideRuntime> configured, CommandSourceStack source) {
        return with(configured, source, guide -> message(source,
                "[TomeWisp] 会话 " + guide.sessions(actor()) + "；当前 " + guide.selectedSession(actor())));
    }

    private static int select(
            ToolResult<ClientGuideRuntime> configured, CommandSourceStack source, String id) {
        return with(configured, source, guide -> {
            guide.selectSession(actor(), id);
            message(source, "[TomeWisp] 已切换到会话 " + id);
        });
    }

    private static int close(
            ToolResult<ClientGuideRuntime> configured, CommandSourceStack source, String id) {
        return with(configured, source, guide -> {
            guide.closeSession(actor(), id);
            message(source, "[TomeWisp] 已关闭会话 " + id);
        });
    }

    private static int status(
            TomeWispRuntime base,
            ToolResult<ClientGuideRuntime> configured,
            NeoForgeClientBridge bridge,
            CommandSourceStack source) {
        if (configured instanceof ToolResult.Success<ClientGuideRuntime> success) {
            message(source, "[TomeWisp] 客户端模型已配置；当前会话 "
                    + success.value().selectedSession(actor())
                    + "；知识文档 " + base.knowledge().snapshot().documents().size()
                    + "；服务端工具 " + bridge.capabilities().remoteTools().size()
                    + "；服务端模型 " + bridge.capabilities().serverModel()
                    + "；当前模式 " + MODEL_MODES.getOrDefault(actor(), "client"));
            return 1;
        }
        failure(source, (ToolResult.Failure<ClientGuideRuntime>) configured);
        return 0;
    }

    private static int skills(TomeWispRuntime base, CommandSourceStack source) {
        message(source, "[TomeWisp] Skills: "
                + base.skills().metadata().stream().map(value -> value.name()).toList());
        return 1;
    }

    private static int sources(TomeWispRuntime base, CommandSourceStack source) {
        reloadKnowledge(base, Minecraft.getInstance());
        message(source, "[TomeWisp] 知识来源: " + base.knowledge().snapshot().documents().stream()
                .map(value -> value.sourceId()).distinct().sorted().toList());
        return 1;
    }

    private static int with(
            ToolResult<ClientGuideRuntime> configured,
            CommandSourceStack source,
            java.util.function.Consumer<ClientGuideRuntime> action) {
        if (configured instanceof ToolResult.Success<ClientGuideRuntime> success) {
            action.accept(success.value());
            return 1;
        }
        failure(source, (ToolResult.Failure<ClientGuideRuntime>) configured);
        return 0;
    }

    private static java.util.UUID actor() {
        return java.util.Objects.requireNonNull(Minecraft.getInstance().player).getUUID();
    }

    private static void message(CommandSourceStack source, String value) {
        source.sendSuccess(() -> Component.literal(value), false);
    }

    private static void failure(
            CommandSourceStack source, ToolResult.Failure<ClientGuideRuntime> failure) {
        source.sendFailure(Component.literal("[TomeWisp] " + failure.code() + ": " + failure.message()));
    }
}
