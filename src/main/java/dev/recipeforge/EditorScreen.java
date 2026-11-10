package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Version 0.1 editor: search a recipe, load its JSON, edit, save and reload.
 * Closes itself if the player leaves creative mode.
 */
public class EditorScreen extends Screen {

    private static final String TEMPLATE = """
            {
              "type": "minecraft:crafting_shaped",
              "category": "misc",
              "pattern": [
                "##",
                "##"
              ],
              "key": {
                "#": { "item": "minecraft:stick" }
              },
              "result": { "id": "minecraft:oak_planks", "count": 1 }
            }
            """;

    private EditBox idBox;
    private MultiLineEditBox jsonBox;
    private final List<Button> matchButtons = new ArrayList<>();
    private String status = "Search for a recipe, click it, press Load, edit, then Save & Reload.";
    private String pendingSelect = null;
    private boolean suppress = false;
    private int left;
    private int boxWidth;

    public EditorScreen() {
        super(Component.literal("Recipe Forge"));
    }

    @Override
    protected void init() {
        String prevId = idBox != null ? idBox.getValue() : "";
        String prevJson = jsonBox != null ? jsonBox.getValue() : "";
        matchButtons.clear();

        boxWidth = Math.min(this.width - 20, 440);
        left = (this.width - boxWidth) / 2;

        idBox = new EditBox(this.font, left, 22, boxWidth - 64, 20, Component.literal("Recipe ID"));
        idBox.setMaxLength(256);
        idBox.setHint(Component.literal("Search or type a recipe id"));
        idBox.setValue(prevId);
        idBox.setResponder(this::onIdChanged);
        this.addRenderableWidget(idBox);

        this.addRenderableWidget(Button.builder(Component.literal("Load"), b -> loadRecipe())
                .bounds(left + boxWidth - 60, 22, 60, 20).build());

        int jsonTop = 98;
        int jsonHeight = Math.max(40, this.height - 56 - jsonTop);
        jsonBox = new MultiLineEditBox(this.font, left, jsonTop, boxWidth, jsonHeight,
                Component.literal("Recipe JSON appears here"), Component.literal("Recipe JSON"));
        jsonBox.setValue(prevJson);
        this.addRenderableWidget(jsonBox);

        int gap = 4;
        int bw = (boxWidth - gap * 4) / 5;
        int by = this.height - 28;
        this.addRenderableWidget(Button.builder(Component.literal("Save & Reload"), b -> save())
                .bounds(left, by, bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Disable"), b -> sendSimple("DISABLE"))
                .bounds(left + (bw + gap), by, bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Restore"), b -> sendSimple("RESTORE"))
                .bounds(left + (bw + gap) * 2, by, bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Template"), b -> template())
                .bounds(left + (bw + gap) * 3, by, bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
                .bounds(left + (bw + gap) * 4, by, bw, 20).build());

        this.setInitialFocus(idBox);
        refreshMatches(prevId);
    }

    private void onIdChanged(String text) {
        if (!suppress) {
            refreshMatches(text);
        }
    }

    private void refreshMatches(String filter) {
        for (Button b : matchButtons) {
            this.removeWidget(b);
        }
        matchButtons.clear();
        if (filter == null || filter.isBlank() || this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        String f = filter.trim().toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (RecipeHolder<?> holder : this.minecraft.level.getRecipeManager().getRecipes()) {
            String s = holder.id().toString();
            if (s.contains(f)) {
                found.add(s);
            }
        }
        found.sort(String::compareTo);
        int count = Math.min(4, found.size());
        for (int i = 0; i < count; i++) {
            final String match = found.get(i);
            Button b = Button.builder(Component.literal(match), btn -> pendingSelect = match)
                    .bounds(left, 44 + i * 13, boxWidth, 12).build();
            matchButtons.add(b);
            this.addRenderableWidget(b);
        }
        if (found.size() > count) {
            status = found.size() + " matches. Showing the first " + count + ". Type more to narrow it down.";
        } else if (found.isEmpty()) {
            status = "No recipe matches that text.";
        }
    }

    private void loadRecipe() {
        if (this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(idBox.getValue().trim());
        if (id == null) {
            status = "That is not a valid recipe id.";
            return;
        }
        Optional<RecipeHolder<?>> holder = this.minecraft.level.getRecipeManager().byKey(id);
        if (holder.isEmpty()) {
            status = "No recipe with id " + id + " exists right now. (Use Template to start a new one.)";
            return;
        }
        RegistryOps<JsonElement> ops = this.minecraft.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        DataResult<JsonElement> result = Recipe.CODEC.encodeStart(ops, holder.get().value());
        if (result.result().isPresent()) {
            jsonBox.setValue(new GsonBuilder().setPrettyPrinting().create().toJson(result.result().get()));
            status = "Loaded " + id + ". Edit the JSON, then press Save & Reload.";
        } else {
            status = "Could not turn this recipe into JSON: "
                    + result.error().map(e -> e.message()).orElse("unknown error");
        }
    }

    private void save() {
        String id = idBox.getValue().trim();
        if (ResourceLocation.tryParse(id) == null || id.isEmpty()) {
            status = "Type a valid recipe id first.";
            return;
        }
        String text = jsonBox.getValue();
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                status = "The recipe must be a JSON object that starts with { and ends with }.";
                return;
            }
        } catch (JsonParseException e) {
            status = "JSON problem: " + e.getMessage();
            return;
        }
        if (text.length() > 29000) {
            status = "This recipe is too large to send (limit about 29000 characters).";
            return;
        }
        PacketDistributor.sendToServer(new RecipeEditPayload("SAVE", id, text));
        status = "Sent. Check the chat for the result.";
    }

    private void sendSimple(String action) {
        String id = idBox.getValue().trim();
        if (ResourceLocation.tryParse(id) == null || id.isEmpty()) {
            status = "Type a valid recipe id first.";
            return;
        }
        PacketDistributor.sendToServer(new RecipeEditPayload(action, id, ""));
        status = "Sent. Check the chat for the result.";
    }

    private void template() {
        jsonBox.setValue(TEMPLATE);
        if (idBox.getValue().isBlank()) {
            suppress = true;
            idBox.setValue("recipeforge:my_recipe");
            suppress = false;
            refreshMatches("");
        }
        status = "Template loaded. Change the id above, edit the JSON, then Save & Reload.";
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft == null || this.minecraft.player == null || !this.minecraft.player.isCreative()) {
            this.onClose();
            return;
        }
        if (pendingSelect != null) {
            String selected = pendingSelect;
            pendingSelect = null;
            suppress = true;
            idBox.setValue(selected);
            suppress = false;
            refreshMatches("");
            loadRecipe();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        graphics.drawString(this.font, this.font.plainSubstrByWidth(status, boxWidth), left, this.height - 42, 0xFFFF55);
    }
}
