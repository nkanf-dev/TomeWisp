package dev.tomewisp.fabric;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import dev.tomewisp.guide.GuideCommandFacade;
import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuideNotice;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

public final class FabricGuideCommands {
    private FabricGuideCommands() {}

    public static void register(GuideCommandFacade guide) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                literal("guide")
                        .executes(context -> invoke(context.getSource(), sink ->
                                guide.open(actor(context.getSource()), sink)))
                        .then(literal("cancel").executes(context -> invoke(context.getSource(), sink ->
                                guide.cancel(actor(context.getSource()), sink))))
                        .then(literal("retry").executes(context -> invoke(context.getSource(), sink ->
                                guide.retry(actor(context.getSource()), sink))))
                        .then(literal("clear").executes(context -> invoke(context.getSource(), sink ->
                                guide.clear(actor(context.getSource()), sink))))
                        .then(literal("status").executes(context -> invoke(context.getSource(), sink ->
                                guide.status(actor(context.getSource()), sink))))
                        .then(literal("skills").executes(context -> invoke(
                                context.getSource(), guide::skills)))
                        .then(literal("sources").executes(context -> invoke(
                                context.getSource(), guide::sources)))
                        .then(literal("model")
                                .then(literal("client").executes(context -> invoke(
                                        context.getSource(), sink -> guide.model(
                                                actor(context.getSource()), GuideModelMode.CLIENT, sink))))
                                .then(literal("server").executes(context -> invoke(
                                        context.getSource(), sink -> guide.model(
                                                actor(context.getSource()), GuideModelMode.SERVER, sink)))))
                        .then(literal("session")
                                .then(literal("list").executes(context -> invoke(
                                        context.getSource(), sink -> guide.sessions(
                                                actor(context.getSource()), sink))))
                                .then(literal("new").then(argument("id", word()).executes(context -> invoke(
                                        context.getSource(), sink -> guide.select(
                                                actor(context.getSource()), getString(context, "id"), sink)))))
                                .then(literal("switch").then(argument("id", word()).executes(context -> invoke(
                                        context.getSource(), sink -> guide.select(
                                                actor(context.getSource()), getString(context, "id"), sink)))))
                                .then(literal("close").then(argument("id", word()).executes(context -> invoke(
                                        context.getSource(), sink -> guide.close(
                                                actor(context.getSource()), getString(context, "id"), sink))))))
                        .then(literal("ask").then(argument("question", greedyString()).executes(context -> invoke(
                                context.getSource(), sink -> guide.ask(
                                        actor(context.getSource()), getString(context, "question"), sink)))))
                        .then(argument("question", greedyString()).executes(context -> invoke(
                                context.getSource(), sink -> guide.ask(
                                        actor(context.getSource()), getString(context, "question"), sink))))));
    }

    private static int invoke(
            FabricClientCommandSource source,
            Consumer<Consumer<GuideNotice>> operation) {
        operation.accept(notice -> publish(source, notice));
        return 1;
    }

    private static UUID actor(FabricClientCommandSource source) {
        return source.getPlayer().getUUID();
    }

    private static void publish(FabricClientCommandSource source, GuideNotice notice) {
        Component message = Component.literal("[TomeWisp] " + notice.message());
        if (notice.level() == GuideNotice.Level.ERROR) {
            source.sendError(message);
        } else {
            source.sendFeedback(message);
        }
    }
}
