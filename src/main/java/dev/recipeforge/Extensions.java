package dev.recipeforge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Lets optional modules (the "extras" source set) plug into the server handler. */
public final class Extensions {
    private Extensions() {}

    public interface ServerAction {
        boolean handle(String action, ServerPlayer player, MinecraftServer server, String id, String data);
    }

    private static final List<ServerAction> SERVER = new CopyOnWriteArrayList<>();

    public static void addServer(ServerAction action) {
        SERVER.add(action);
    }

    public static boolean dispatchServer(String action, ServerPlayer player, MinecraftServer server, String id, String data) {
        for (ServerAction a : SERVER) {
            if (a.handle(action, player, server, id, data)) {
                return true;
            }
        }
        return false;
    }

    /** Starts an optional module if its class is part of this jar. */
    public static void tryInit(String className) {
        try {
            Class.forName(className).getMethod("init").invoke(null);
            WorldForge.LOGGER.info("World Forge module loaded: {}", className);
        } catch (ClassNotFoundException e) {
            // module is not part of this build
        } catch (Exception e) {
            WorldForge.LOGGER.error("World Forge module failed to start: " + className, e);
        }
    }
}
