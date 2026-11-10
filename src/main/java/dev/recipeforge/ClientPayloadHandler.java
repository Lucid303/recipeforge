package dev.recipeforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Runs on the client when the server sends us something. */
public final class ClientPayloadHandler {
    private ClientPayloadHandler() {}

    public static void handle(ServerMessagePayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Screen screen = Minecraft.getInstance().screen;
            if (!(screen instanceof ServerFeedbackListener listener)) {
                return;
            }
            switch (p.kind()) {
                case "OK" -> listener.onServerResult(true, p.data());
                case "FAIL" -> listener.onServerResult(false, p.data());
                case "RECIPE" -> listener.onRecipeJson(p.id(), p.data());
                case "RECIPE_ERROR" -> listener.onRecipeError(p.id(), p.data());
                case "HISTORY" -> listener.onHistory(p.data());
                default -> { }
            }
        });
    }
}
