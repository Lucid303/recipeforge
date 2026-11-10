package dev.recipeforge;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** "What kind of recipe?" - one big button per Minecraft station. */
public class RecipeTypeChooserScreen extends ForgeScreen {
    private record Option(String label, String type, Item icon) {}

    private static final List<Option> OPTIONS = List.of(
            new Option("Crafting table", "minecraft:crafting_shaped", Items.CRAFTING_TABLE),
            new Option("Furnace", "minecraft:smelting", Items.FURNACE),
            new Option("Blast furnace", "minecraft:blasting", Items.BLAST_FURNACE),
            new Option("Smoker", "minecraft:smoking", Items.SMOKER),
            new Option("Campfire", "minecraft:campfire_cooking", Items.CAMPFIRE),
            new Option("Stonecutter", "minecraft:stonecutting", Items.STONECUTTER),
            new Option("Smithing table", "minecraft:smithing_transform", Items.SMITHING_TABLE));

    private final String presetResult;
    private int bx;
    private int top;

    public RecipeTypeChooserScreen(Screen parent, String presetResult) {
        super(Component.literal("What kind of recipe?"), parent);
        this.presetResult = presetResult;
    }

    @Override
    protected void init() {
        int bw = 170;
        bx = (this.width - bw) / 2;
        top = 34;
        int stepY = Math.max(21, Math.min(26, (this.height - top - 40) / OPTIONS.size()));
        for (int i = 0; i < OPTIONS.size(); i++) {
            final Option option = OPTIONS.get(i);
            this.addRenderableWidget(Button.builder(Component.literal(option.label()), b ->
                    this.minecraft.setScreen(new GridEditorScreen(parent, null, null, presetResult, option.type())))
                    .bounds(bx, top + i * stepY, bw, 20).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(bx, this.height - 28, bw, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        int stepY = Math.max(21, Math.min(26, (this.height - top - 40) / OPTIONS.size()));
        for (int i = 0; i < OPTIONS.size(); i++) {
            g.renderItem(new ItemStack(OPTIONS.get(i).icon()), bx - 22, top + i * stepY + 2);
        }
    }
}
