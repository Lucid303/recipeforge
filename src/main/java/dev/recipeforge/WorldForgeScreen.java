package dev.recipeforge;

import java.util.List;
import java.util.function.Function;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The main World Forge screen: one big tile per module. Hover a tile for a short description. */
public class WorldForgeScreen extends ForgeScreen {
    private record Tile(String label, String hint, Item icon, Function<Screen, Screen> opener) {}

    private static final List<Tile> TILES = List.of(
            new Tile("Recipe Forge", "Edit crafting, furnace, smithing, anvil and grindstone recipes.",
                    Items.CRAFTING_TABLE, ItemBrowserScreen::new),
            new Tile("Loot Forge", "Change what blocks, mobs and chests drop.", Items.CHEST, LootBrowserScreen::new),
            new Tile("Structure Forge", "Choose which biomes each structure can spawn in.",
                    Items.STRUCTURE_BLOCK, StructureForgeScreen::new),
            new Tile("Biome Forge", "Change grass, leaf, water, sky and fog colors.",
                    Items.GRASS_BLOCK, BiomeForgeScreen::new),
            new Tile("History", "See every change with before and after pictures.",
                    Items.WRITABLE_BOOK, HistoryScreen::new));

    private int tileW;
    private final int tileH = 40;
    private int left;
    private final int top = 50;
    private final int rowStep = 52;

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
                    .bounds(x, y, tileW, tileH)
                    .tooltip(Tooltip.create(Component.literal(tile.hint()))).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
                .bounds(this.width - 78, this.height - 28, 70, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, StationInfo.hub(), "Creative mode only");
        g.drawCenteredString(this.font, "Pick a forge", this.width / 2, 32, 0xAAAAAA);
        for (int i = 0; i < TILES.size(); i++) {
            int x = left + (i % 3) * (tileW + 8);
            int y = top + (i / 3) * rowStep;
            g.renderItem(new ItemStack(TILES.get(i).icon()), x + 6, y + 12);
        }
    }
}
