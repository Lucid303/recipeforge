package dev.recipeforge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;

/** Client side of the optional modules: extra hub tiles and what a right-click in History opens. */
public final class ExtensionsClient {
    private ExtensionsClient() {}

    public record Tile(String label, String hint, Item icon, Function<Screen, Screen> opener) {}

    private static final List<Tile> TILES = new ArrayList<>();
    private static final Map<String, BiFunction<Screen, String, Screen>> HISTORY_OPENERS = new HashMap<>();

    public static void addTile(Tile tile) {
        TILES.add(tile);
    }

    public static List<Tile> tiles() {
        return TILES;
    }

    /** module is the History module name, e.g. "tag". The function gets the parent screen and the history id. */
    public static void addHistoryOpener(String module, BiFunction<Screen, String, Screen> opener) {
        HISTORY_OPENERS.put(module, opener);
    }

    public static BiFunction<Screen, String, Screen> historyOpener(String module) {
        return HISTORY_OPENERS.get(module);
    }
}
