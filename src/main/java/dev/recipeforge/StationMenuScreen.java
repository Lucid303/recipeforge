package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Menu for the stations that are not item recipes: anvil, grindstone, loom (and brewing stand if included). */
public class StationMenuScreen extends ForgeScreen {
    private record Option(String label, Item icon, Function<Screen, Screen> opener) {}

    private final List<Option> options = new ArrayList<>();
    private int bx;
    private int top;
    private int stepY;

    public StationMenuScreen(Screen parent) {
        super(Component.literal("Other stations"), parent);
    }

    @Override
    protected void init() {
        options.clear();
        options.add(new Option("Anvil", Items.ANVIL, p -> new StationListScreen(p, "anvil")));
        options.add(new Option("Grindstone", Items.GRINDSTONE, p -> new StationListScreen(p, "grindstone")));
        options.add(new Option("Loom", Items.LOOM, LoomScreen::new));
        for (ClientModules.Extra extra : ClientModules.extras()) {
            options.add(new Option(extra.label(), extra.icon(), extra.opener()));
        }

        int bw = 170;
        bx = (this.width - bw) / 2;
        top = 40;
        stepY = Math.max(24, Math.min(30, (this.height - top - 40) / Math.max(1, options.size())));
        for (int i = 0; i < options.size(); i++) {
            final Option option = options.get(i);
            this.addRenderableWidget(Button.builder(Component.literal(option.label()),
                    b -> this.minecraft.setScreen(option.opener().apply(this)))
                    .bounds(bx, top + i * stepY, bw, 20).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(bx, this.height - 28, bw, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
        g.drawCenteredString(this.font, "These are not normal recipes, so they have their own editors",
                this.width / 2, 26, 0xAAAAAA);
        for (int i = 0; i < options.size(); i++) {
            g.renderItem(new ItemStack(options.get(i).icon()), bx - 22, top + i * stepY + 2);
        }
    }
}
