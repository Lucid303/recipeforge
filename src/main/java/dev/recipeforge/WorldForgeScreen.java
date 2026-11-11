package dev.recipeforge;

import java.util.List;
import java.util.function.Function;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The main World Forge screen: one big tile per module. */
public class WorldForgeScreen extends ForgeScreen {
    private record Tile(String label, String hint, Item icon, Function<Screen, Screen> opener) {}

    private static final List<Tile> TILES = List.of(
            new Tile("Recipe Forge", "Crafting, furnaces, smithing...", Items.CRAFTING_TABLE, ItemBrowserScreen::new),
            new Tile("Loot Forge", "Drops and chest loot", Items.CHEST, LootBrowserScreen::new),
            new Tile("Structure Forge", "Where structures spawn, blocks", Items.STRUCTURE_BLOCK, StructureForgeScreen::new),
            new Tile("Biome Forge", "Grass, leaf, water, sky colors", Items.GRASS_BLOCK, BiomeForgeScreen::new),
            new Tile("Station Forge", "Anvil, grindstone, loom, brewing", Items.ANVIL, StationMenuScreen::new),
            new Tile("History", "Every change, before and after", Items.WRITABLE_BOOK, HistoryScreen::new));

    private int tileW;
    private int tileH = 34;
    private int left;
    private int top = 44;
    private int rowStep = 58;

    public WorldForgeScreen() {
        super(Component.literal("World Forge"), null);
    }

    @Override
    protected void init() {
        tileW = Math.max(120, Math.min(160, (this.width - 40) / 3));
        left = (this.width - (tileW * 3 + 16)) / 2;
        for (int i = 0; i < TILES.size(); i++) {
            final Tile tile = TILES.get(i);
            int x = left + (i % 3) * (tileW + 8);
            int y = top + (i / 3) * rowStep;
            this.addRenderableWidget(Button.builder(Component.literal("    " + tile.label()),
                    b -> this.minecraft.setScreen(tile.opener().apply(this)))
                    .bounds(x, y, tileW, tileH).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
                .bounds(this.width / 2 - 40, this.height - 28, 80, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, StationInfo.hub(), "Creative mode only");
        g.drawCenteredString(this.font, "Pick a forge", this.width / 2, 28, 0xAAAAAA);
        for (int i = 0; i < TILES.size(); i++) {
            Tile tile = TILES.get(i);
            int x = left + (i % 3) * (tileW + 8);
            int y = top + (i / 3) * rowStep;
            g.renderItem(new ItemStack(tile.icon()), x + 6, y + 9);
            g.drawString(this.font, this.font.plainSubstrByWidth(tile.hint(), tileW), x, y + tileH + 4, 0xAAAAAA);
        }
    }
}
