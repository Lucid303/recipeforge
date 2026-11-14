package dev.recipeforge.extras;

import dev.recipeforge.ExtensionsClient;
import net.minecraft.world.item.Items;

/** Client side entry point of the optional modules: hub tiles and History shortcuts. */
public final class ExtrasClient {
    private ExtrasClient() {}

    /** Called by World Forge's client when this module is part of the jar. */
    public static void init() {
        ExtensionsClient.addTile(new ExtensionsClient.Tile("Tag Forge", "Add or remove items from tags like planks or ores.",
                Items.NAME_TAG, TagForgeScreen::new));
        ExtensionsClient.addTile(new ExtensionsClient.Tile("Enchantment Forge", "Change enchantment levels, costs and items.",
                Items.ENCHANTING_TABLE, p -> new DataListScreen(p, true)));
        ExtensionsClient.addTile(new ExtensionsClient.Tile("World Gen Forge", "Change how many ores generate, how high and how big.",
                Items.IRON_ORE, p -> new DataListScreen(p, false)));

        ExtensionsClient.addHistoryOpener("tag", (parent, id) -> {
            String[] parts = id.split("\\|", 2);
            return new TagScreen(parent, parts[0], parts.length > 1 ? parts[1] : id);
        });
        ExtensionsClient.addHistoryOpener("enchantment", EnchantmentScreen::new);
        ExtensionsClient.addHistoryOpener("worldgen", GenScreen::new);
    }
}
