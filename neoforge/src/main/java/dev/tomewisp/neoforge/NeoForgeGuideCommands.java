package dev.tomewisp.neoforge;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import dev.tomewisp.guide.GuideCommandFacade;
import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuideNotice;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class NeoForgeGuideCommands {
    private NeoForgeGuideCommands() {}

    public static void register(GuideCommandFacade guide) {
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> event.getDispatcher().register(
                literal("guide")
                        .executes(context -> invoke(context.getSource(), sink -> guide.open(actor(), sink)))
                        .then(literal("cancel").executes(context -> invoke(
                                context.getSource(), sink -> guide.cancel(actor(), sink))))
                        .then(literal("retry").executes(context -> invoke(
                                context.getSource(), sink -> guide.retry(actor(), sink))))
                        .then(literal("clear").executes(context -> invoke(
                                context.getSource(), sink -> guide.clear(actor(), sink))))
                        .then(literal("status").executes(context -> invoke(
                                context.getSource(), sink -> guide.status(actor(), sink))))
                        .then(literal("skills").executes(context -> invoke(
                                context.getSource(), guide::skills)))
                        .then(literal("sources").executes(context -> invoke(
                                context.getSource(), guide::sources)))
                        .then(literal("model")
                                .then(literal("list").executes(context -> invoke(
                                        context.getSource(), sink -> guide.models(actor(), sink))))
                                .then(literal("profile").then(argument("id", word()).executes(
                                        context -> invoke(context.getSource(), sink -> guide.modelProfile(
                                                actor(), getString(context, "id"), sink)))))
                                .then(literal("client").executes(context -> invoke(
                                        context.getSource(), sink -> guide.model(
                                                actor(), GuideModelMode.CLIENT, sink))))
                                .then(literal("server").executes(context -> invoke(
                                        context.getSource(), sink -> guide.model(
                                                actor(), GuideModelMode.SERVER, sink)))))
                        .then(literal("session")
                                .then(literal("list").executes(context -> invoke(
                                        context.getSource(), sink -> guide.sessions(actor(), sink))))
                                .then(literal("new").then(argument("id", word()).executes(context -> invoke(
                                        context.getSource(), sink -> guide.select(
                                                actor(), getString(context, "id"), sink)))))
                                .then(literal("switch").then(argument("id", word()).executes(context -> invoke(
                                        context.getSource(), sink -> guide.select(
                                                actor(), getString(context, "id"), sink)))))
                                .then(literal("close").then(argument("id", word()).executes(context -> invoke(
                                        context.getSource(), sink -> guide.close(
                                                actor(), getString(context, "id"), sink))))))
                        .then(literal("ask").then(argument("question", greedyString()).executes(context -> invoke(
                                context.getSource(), sink -> guide.ask(
                                        actor(), getString(context, "question"), sink)))))
                        .then(argument("question", greedyString()).executes(context -> invoke(
                                context.getSource(), sink -> guide.ask(
                                        actor(), getString(context, "question"), sink))))));
    }

    private static int invoke(
            CommandSourceStack source,
            Consumer<Consumer<GuideNotice>> operation) {
        operation.accept(notice -> publish(source, notice));
        return 1;
    }

    private static UUID actor() {
        return java.util.Objects.requireNonNull(Minecraft.getInstance().player).getUUID();
    }

    private static void publish(CommandSourceStack source, GuideNotice notice) {
        Component message = Component.literal("[TomeWisp] " + notice.message());
        if (notice.level() == GuideNotice.Level.ERROR) {
            source.sendFailure(message);
        } else {
            source.sendSuccess(() -> message, false);
        }
    }
}
