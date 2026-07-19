package dev.openallay.client;

import dev.openallay.guide.history.GuideHistoryException;
import dev.openallay.guide.history.GuideHistoryScope;
import dev.openallay.guide.history.GuideHistoryScopeProvider;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

/** Captures and detaches the active connection identity on the client thread. */
public final class MinecraftGuideHistoryScope implements GuideHistoryScopeProvider {
    private final Minecraft client;

    public MinecraftGuideHistoryScope(Minecraft client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public GuideHistoryScope resolve(UUID actor) {
        Objects.requireNonNull(actor, "actor");
        if (!client.isSameThread()) {
            throw new GuideHistoryException(
                    "history_scope_thread", "Guide history scope must be captured on the client thread");
        }
        if (client.player == null || !client.player.getUUID().equals(actor)) {
            throw unavailable();
        }
        IntegratedServer integrated = client.getSingleplayerServer();
        Path worldPath = integrated == null
                ? null
                : integrated.getWorldPath(LevelResource.ROOT);
        ServerData server = client.getCurrentServer();
        return detached(actor, worldPath, server == null ? null : server.ip);
    }

    static GuideHistoryScope detached(UUID actor, Path worldPath, String serverAddress) {
        Objects.requireNonNull(actor, "actor");
        boolean hasWorld = worldPath != null;
        boolean hasServer = serverAddress != null && !serverAddress.isBlank();
        if (hasWorld == hasServer) {
            throw unavailable();
        }
        if (hasWorld) {
            return GuideHistoryScope.derive(
                    actor,
                    GuideHistoryScope.Kind.SINGLEPLAYER,
                    worldPath.toAbsolutePath().normalize().toString());
        }
        return GuideHistoryScope.derive(
                actor, GuideHistoryScope.Kind.MULTIPLAYER, serverAddress);
    }

    private static GuideHistoryException unavailable() {
        return new GuideHistoryException(
                "history_scope_unavailable", "No active Minecraft connection is available");
    }
}
