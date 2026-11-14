package dev.recipeforge.extras;

import dev.recipeforge.Extensions;

/** Server side entry point of the optional modules (Tag Forge, Enchantment Forge, World Gen Forge). */
public final class ExtrasModule {
    private ExtrasModule() {}

    /** Called by World Forge when this module is part of the jar. */
    public static void init() {
        Extensions.addServer((action, player, server, id, data) ->
                TagServer.handle(action, player, server, id, data) || DataServer.handle(action, player, server, id, data));
    }
}
