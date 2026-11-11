package dev.recipeforge;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/** Small drawing helpers shared by the screens. */
public final class Ui {
    private Ui() {}

    public static void slot(GuiGraphics g, int x, int y, int size) {
        g.fill(x, y, x + size, y + size, 0xFF8B8B8B);
        g.fill(x + 1, y + 1, x + size - 1, y + size - 1, 0xFF373737);
    }

    public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Picks one of several items, switching once per second (for tags with many items). */
    public static ItemStack cycle(ItemStack[] items) {
        if (items == null || items.length == 0) {
            return ItemStack.EMPTY;
        }
        int index = (int) ((System.currentTimeMillis() / 1000L) % items.length);
        return items[index];
    }
}
