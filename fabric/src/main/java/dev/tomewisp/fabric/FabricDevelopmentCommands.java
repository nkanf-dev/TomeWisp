package dev.tomewisp.fabric;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.devmode.DevelopmentCommandHandler;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.trace.replay.ReplayReport;
import java.util.concurrent.CompletableFuture;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

public final class FabricDevelopmentCommands {
    private FabricDevelopmentCommands() {}

    public static void register(TomeWispRuntime runtime) {
        DevelopmentCommandHandler handler =
                new DevelopmentCommandHandler(runtime.developmentTools());
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
                dispatcher.register(literal("tomewisp")
                        .then(literal("dev")
                                .requires(source -> source.permissions()
                                        .hasPermission(Permissions.COMMANDS_GAMEMASTER))
                                .then(literal("tools").executes(context -> {
                                    handler.listTools().forEach(line -> context.getSource()
                                            .sendSuccess(() -> Component.literal(line), false));
                                    return 1;
                                }))
                                .then(literal("replay")
                                        .then(argument("trace", word())
                                                .suggests((context, builder) -> suggestTraces(
                                                        runtime,
                                                        context.getSource(),
                                                        builder))
                                                .executes(context -> replay(
                                                        runtime,
                                                        context.getSource(),
                                                        getString(context, "trace")))))
                                .then(literal("invoke")
                                        .then(argument("tool", greedyString())
                                                .executes(context -> {
                                                    String id = getString(context, "tool");
                                                    context.getSource().sendSuccess(
                                                            () -> Component.literal(
                                                                    handler.invoke(id)),
                                                            false);
                                                    return 1;
                                                }))))));
    }

    private static CompletableFuture<Suggestions> suggestTraces(
            TomeWispRuntime runtime, CommandSourceStack source, SuggestionsBuilder builder) {
        return switch (runtime.traceReplay().traceIds(source)) {
            case ToolResult.Success<?> success -> SharedSuggestionProvider.suggest(
                    ((java.util.List<?>) success.value()).stream()
                            .map(Object::toString)
                            .toList(),
                    builder);
            case ToolResult.Failure<?> failure -> builder.buildFuture();
        };
    }

    private static int replay(
            TomeWispRuntime runtime, CommandSourceStack source, String traceId) {
        return switch (runtime.traceReplay().replay(source, traceId)) {
            case ToolResult.Success<ReplayReport> success -> {
                success.value().chatLines().forEach(line ->
                        source.sendSuccess(() -> Component.literal(line), false));
                yield success.value().passed() ? 1 : 0;
            }
            case ToolResult.Failure<ReplayReport> failure -> {
                source.sendFailure(Component.literal(
                        "FAILURE " + failure.code() + ": " + failure.message()));
                yield 0;
            }
        };
    }
}
