package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;

/** Client-only list of extra stations that add-on modules put in the "Other stations" menu. */
public final class ClientModules {
    private ClientModules() {}

    public record Extra(String label, Item icon, Function<Screen, Screen> opener) {}

    private static final List<Extra> EXTRAS = new ArrayList<>();

    public static void addStation(Extra extra) {
        EXTRAS.add(extra);
    }

    public static List<Extra> extras() {
        return EXTRAS;
    }
}
