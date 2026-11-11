package dev.recipeforge;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Base for all World Forge screens: creative-only guard, Esc goes back one screen,
 * scroll wheel changes pages, shared page buttons and word-wrapped status text.
 */
public abstract class ForgeScreen extends Screen implements ServerFeedbackListener {
    protected final Screen parent;
    protected String status = "";

    protected ForgeScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft == null) {
            return;
        }
        if (this.minecraft.player == null || !this.minecraft.player.isCreative()) {
            this.minecraft.setScreen(null);
            return;
        }
        onForgeTick();
    }

    protected void onForgeTick() {}

    /** Draw row backgrounds etc. here: it runs BEFORE the buttons, so buttons keep their normal colours. */
    protected void drawUnderWidgets(GuiGraphics g, int mouseX, int mouseY) {}

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        drawUnderWidgets(g, mouseX, mouseY);
    }

    /** Called when the mouse wheel moves over a screen with pages. -1 = previous page, +1 = next page. */
    protected void scrollPage(int delta) {}

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        if (scrollY != 0) {
            scrollPage(scrollY > 0 ? -1 : 1);
            return true;
        }
        return false;
    }

    /** "<" and ">" buttons with enough room between them for the page text. */
    protected void addPager(int centerX, int y, Runnable prev, Runnable next) {
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> prev.run())
                .bounds(centerX - 62, y, 22, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> next.run())
                .bounds(centerX + 40, y, 22, 20).build());
    }

    protected void drawPager(GuiGraphics g, int centerX, int y, int page, int pages) {
        g.drawCenteredString(this.font, "Page " + (page + 1) + " / " + Math.max(1, pages), centerX, y + 6, 0xAAAAAA);
    }

    /** Status text, wrapped over up to two lines so nothing is cut off. */
    protected void drawStatus(GuiGraphics g, int x, int y, int maxWidth) {
        if (status == null || status.isEmpty()) {
            return;
        }
        List<FormattedCharSequence> lines = this.font.split(Component.literal(status), Math.max(60, maxWidth));
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            g.drawString(this.font, lines.get(i), x, y + i * 10, 0xFFFF55);
        }
    }

    @Override
    public void onServerResult(boolean ok, String message) {
        status = message;
    }
}
