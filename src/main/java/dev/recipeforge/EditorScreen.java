package dev.recipeforge;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/** Text editor for machine recipes (Create, Mekanism, Ars Nouveau, ...) that are not crafting-table recipes. */
public class EditorScreen extends ForgeScreen {
    private final String startId;
    private final String startJson;
    private EditBox idBox;
    private MultiLineEditBox jsonBox;
    private int left;
    private int boxWidth;

    public EditorScreen(Screen parent, String id, String json) {
        super(Component.literal("Recipe Forge - advanced editor"), parent);
        this.startId = id;
        this.startJson = json;
        this.status = "This recipe is edited as text. Change a number or item name, then press Save.";
    }

    @Override
    protected void init() {
        String prevId = idBox != null ? idBox.getValue() : startId;
        String prevJson = jsonBox != null ? jsonBox.getValue() : startJson;

        boxWidth = Math.min(this.width - 20, 440);
        left = (this.width - boxWidth) / 2;

        idBox = new EditBox(this.font, left, 30, boxWidth, 20, Component.literal("Recipe ID"));
        idBox.setMaxLength(256);
        idBox.setValue(prevId);
        this.addRenderableWidget(idBox);

        int top = 56;
        jsonBox = new MultiLineEditBox(this.font, left, top, boxWidth, Math.max(40, this.height - top - 56),
                Component.literal("Recipe JSON"), Component.literal("Recipe JSON"));
        jsonBox.setValue(prevJson);
        this.addRenderableWidget(jsonBox);

        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(left, this.height - 28, 90, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
                .bounds(left + 96, this.height - 28, 90, 20).build());
    }

    private void save() {
        String id = idBox.getValue().trim();
        if (id.isEmpty() || ResourceLocation.tryParse(id) == null) {
            status = "The recipe id is not valid.";
            return;
        }
        String text = jsonBox.getValue();
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                status = "The recipe must start with { and end with }.";
                return;
            }
        } catch (JsonParseException e) {
            status = "JSON problem: " + e.getMessage();
            return;
        }
        if (text.length() > 29000) {
            status = "This recipe is too big to send.";
            return;
        }
        PacketDistributor.sendToServer(new RecipeEditPayload("SAVE", id, text));
        status = "Saving...";
    }

    @Override
    public void onServerResult(boolean ok, String message) {
        status = message;
        if (ok && this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        g.drawString(this.font, "Recipe id (change it to save a copy instead of replacing):", left, 19, 0xAAAAAA);
        drawStatus(g, left, this.height - 42, boxWidth);
    }
}
