package dev.recipeforge;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class Network {
    private Network() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("2");
        registrar.playToServer(RecipeEditPayload.TYPE, RecipeEditPayload.CODEC, ServerHandler::handle);
        registrar.playToClient(ServerMessagePayload.TYPE, ServerMessagePayload.CODEC, ClientPayloadHandler::handle);
    }
}
