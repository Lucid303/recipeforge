package dev.recipeforge;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Base for all Recipe Forge screens: creative-only guard, Esc goes back one screen. */
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

    protected void drawStatus(GuiGraphics g, int x, int y, int maxWidth) {
        g.drawString(this.font, this.font.plainSubstrByWidth(status, maxWidth), x, y, 0xFFFF55);
    }

    @Override
    public void onServerResult(boolean ok, String message) {
        status = message;
    }
}
