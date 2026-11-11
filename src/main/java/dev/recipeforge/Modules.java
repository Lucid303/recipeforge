package dev.recipeforge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Lets optional add-on code (the brewing module) plug into the server and client handlers. */
public final class Modules {
    private Modules() {}

    public interface ServerAction {
        boolean handle(String action, ServerPlayer player, MinecraftServer server, String id, String data);
    }

    public interface ClientMessage {
        boolean handle(String kind, String id, String data);
    }

    private static final List<ServerAction> SERVER = new CopyOnWriteArrayList<>();
    private static final List<ClientMessage> CLIENT = new CopyOnWriteArrayList<>();

    public static void addServer(ServerAction action) {
        SERVER.add(action);
    }

    public static void addClient(ClientMessage message) {
        CLIENT.add(message);
    }

    public static boolean dispatchServer(String action, ServerPlayer player, MinecraftServer server, String id, String data) {
        for (ServerAction a : SERVER) {
            if (a.handle(action, player, server, id, data)) {
                return true;
            }
        }
        return false;
    }

    public static boolean dispatchClient(String kind, String id, String data) {
        for (ClientMessage m : CLIENT) {
            if (m.handle(kind, id, data)) {
                return true;
            }
        }
        return false;
    }

    /** Starts an optional module if its class is part of this jar. */
    public static void tryInit(String className) {
        try {
            Class.forName(className).getMethod("init").invoke(null);
            WorldForge.LOGGER.info("Recipe Forge module loaded: {}", className);
        } catch (ClassNotFoundException e) {
            // module is not part of this build
        } catch (Exception e) {
            WorldForge.LOGGER.error("Recipe Forge module failed to start: " + className, e);
        }
    }
}
