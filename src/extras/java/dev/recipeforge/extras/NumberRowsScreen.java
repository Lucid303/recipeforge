package dev.recipeforge.extras;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.recipeforge.ForgeScreen;
import dev.recipeforge.StationInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A screen made of rows "name  value  -5 -1 +1 +5", used by the Enchantment and World Gen editors. */
public abstract class NumberRowsScreen extends ForgeScreen {
    protected record Row(String label, Supplier<Integer> get, Consumer<Integer> set, int min, int max) {}

    protected static final int ROW_H = 22;
    protected final List<Row> numberRows = new ArrayList<>();
    protected final StationInfo.Info info;
    protected final String subtitle;
    protected int left;
    protected int boxWidth;
    protected final int top = 34;

    protected NumberRowsScreen(Screen parent, StationInfo.Info info, String subtitle) {
        super(Component.literal(info.name()), parent);
        this.info = info;
        this.subtitle = subtitle;
    }

    protected abstract void sendSave();

    protected abstract void sendRestore();

    /** Extra widgets (for example a "Change" button) under the number rows. y is the first free row. */
    protected void addExtraWidgets(int y) {}

    protected void drawExtra(GuiGraphics g, int y) {}

    protected void requestData() {}

    private boolean requested = false;

    @Override
    protected void init() {
        if (!requested) {
            requested = true;
            requestData();
        }
        boxWidth = Math.min(this.width - 20, 400);
        left = (this.width - boxWidth) / 2;
        for (int i = 0; i < numberRows.size(); i++) {
            final Row row = numberRows.get(i);
            int y = top + i * ROW_H;
            int[] steps = {-5, -1, 1, 5};
            for (int s = 0; s < 4; s++) {
                final int step = steps[s];
                this.addRenderableWidget(Button.builder(Component.literal(step > 0 ? "+" + step : String.valueOf(step)),
                        b -> row.set().accept(Math.max(row.min(), Math.min(row.max(), row.get().get() + step))))
                        .bounds(left + 230 + s * 28, y, 26, 18)
                        .tooltip(Tooltip.create(Component.literal(step > 0 ? "Raise by " + step : "Lower by " + (-step)))).build());
            }
        }
        addExtraWidgets(top + numberRows.size() * ROW_H);

        int by = this.height - 28;
        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> sendSave())
                .bounds(left, by, 70, 20)
                .tooltip(Tooltip.create(Component.literal("Apply to the game. You must leave and re-enter the world."))).build());
        this.addRenderableWidget(Button.builder(Component.literal("Restore"), b -> sendRestore())
                .bounds(left + 74, by, 70, 20)
                .tooltip(Tooltip.create(Component.literal("Put the original values back."))).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width - 78, by, 70, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, info, subtitle);
        for (int i = 0; i < numberRows.size(); i++) {
            Row row = numberRows.get(i);
            int y = top + i * ROW_H;
            g.drawString(this.font, this.font.plainSubstrByWidth(row.label(), 170), left + 4, y + 5, 0xFFFFFF);
            g.drawString(this.font, String.valueOf(row.get().get()), left + 190, y + 5, 0xFFFF55);
        }
        drawExtra(g, top + numberRows.size() * ROW_H);
        drawStatus(g, left, this.height - 52, boxWidth);
    }
}
