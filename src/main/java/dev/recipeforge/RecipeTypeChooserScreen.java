package dev.recipeforge;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** "What kind of recipe?" - one button per station. */
public class RecipeTypeChooserScreen extends ForgeScreen {
    private record Option(String label, String type, Item icon, String hint) {}

    private static final List<Option> OPTIONS = List.of(
            new Option("Crafting table", "minecraft:crafting_shaped", Items.CRAFTING_TABLE, "A normal crafting recipe."),
            new Option("Furnace", "minecraft:smelting", Items.FURNACE, "Smelting."),
            new Option("Blast furnace", "minecraft:blasting", Items.BLAST_FURNACE, "Fast smelting for ores and metal."),
            new Option("Smoker", "minecraft:smoking", Items.SMOKER, "Fast cooking for food."),
            new Option("Campfire", "minecraft:campfire_cooking", Items.CAMPFIRE, "Slow cooking on a campfire."),
            new Option("Stonecutter", "minecraft:stonecutting", Items.STONECUTTER, "One item in, items out."),
            new Option("Smithing table", "minecraft:smithing_transform", Items.SMITHING_TABLE,
                    "Upgrade items with a template, a base and an addition."),
            new Option("Anvil", "recipeforge:anvil", Items.ANVIL, "Two items combine into a new item for XP levels."),
            new Option("Anvil (block)", "recipeforge:anvil_block", Items.ANVIL, "Stop two items from being combined."),
            new Option("Grindstone", "recipeforge:grindstone", Items.GRINDSTONE, "Two items combine into a new item."),
            new Option("Grindstone (block)", "recipeforge:grindstone_block", Items.GRINDSTONE,
                    "Stop two items from being ground together."));

    private final String presetResult;
    private int colLeft;
    private int colRight;
    private final int top = 34;
    private final int stepY = 26;

    public RecipeTypeChooserScreen(Screen parent, String presetResult) {
        super(Component.literal("What kind of recipe?"), parent);
        this.presetResult = presetResult;
    }

    @Override
    protected void init() {
        int bw = 150;
        colLeft = this.width / 2 - bw - 14;
        colRight = this.width / 2 + 14;
        int perCol = (OPTIONS.size() + 1) / 2;
        for (int i = 0; i < OPTIONS.size(); i++) {
            final Option option = OPTIONS.get(i);
            int x = i < perCol ? colLeft : colRight;
            int y = top + (i % perCol) * stepY;
            this.addRenderableWidget(Button.builder(Component.literal(option.label()), b ->
                    this.minecraft.setScreen(new GridEditorScreen(parent, null, null, presetResult, option.type())))
                    .bounds(x, y, bw, 20).tooltip(Tooltip.create(Component.literal(option.hint()))).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width - 78, this.height - 28, 70, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, StationInfo.recipes(), "What kind of recipe?");
        int perCol = (OPTIONS.size() + 1) / 2;
        for (int i = 0; i < OPTIONS.size(); i++) {
            int x = i < perCol ? colLeft : colRight;
            int y = top + (i % perCol) * stepY;
            g.renderItem(new ItemStack(OPTIONS.get(i).icon()), x - 20, y + 2);
        }
    }
}
