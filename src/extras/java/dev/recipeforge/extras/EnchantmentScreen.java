package dev.recipeforge.extras;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.recipeforge.RecipeEditPayload;
import dev.recipeforge.StationInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/** Edits one enchantment: highest level, how common it is, costs and which items it applies to. */
public class EnchantmentScreen extends NumberRowsScreen {
    static final StationInfo.Info INFO = new StationInfo.Info("Enchantment Forge", Items.ENCHANTING_TABLE, 0xFF5B3A8A);
    private static final Gson GSON = new GsonBuilder().create();

    private final String id;
    private JsonObject json = null;

    public EnchantmentScreen(Screen parent, String id) {
        super(parent, INFO, id);
        this.id = id;
    }

    @Override
    protected void requestData() {
        PacketDistributor.sendToServer(new RecipeEditPayload("ENCH_GET", id, ""));
    }

    private boolean has(String... path) {
        JsonObject o = json;
        for (int i = 0; i < path.length - 1; i++) {
            o = o != null && o.has(path[i]) && o.get(path[i]).isJsonObject() ? o.getAsJsonObject(path[i]) : null;
        }
        return o != null && o.has(path[path.length - 1]) && o.get(path[path.length - 1]).isJsonPrimitive();
    }

    private int get(String... path) {
        JsonObject o = json;
        for (int i = 0; i < path.length - 1; i++) {
            o = o.getAsJsonObject(path[i]);
        }
        return o.get(path[path.length - 1]).getAsInt();
    }

    private void set(int value, String... path) {
        JsonObject o = json;
        for (int i = 0; i < path.length - 1; i++) {
            o = o.getAsJsonObject(path[i]);
        }
        o.addProperty(path[path.length - 1], value);
    }

    private void addRow(String label, int min, int max, String... path) {
        if (has(path)) {
            numberRows.add(new Row(label, () -> get(path), v -> set(v, path), min, max));
        }
    }

    @Override
    public void onData(String kind, String dataId, String data) {
        if (!dataId.equals(id)) {
            return;
        }
        if (kind.equals("ENCH")) {
            try {
                json = JsonParser.parseString(data).getAsJsonObject();
                numberRows.clear();
                addRow("Highest level", 1, 255, "max_level");
                addRow("How common (weight)", 1, 1024, "weight");
                addRow("Anvil cost", 0, 1000, "anvil_cost");
                addRow("Lowest cost: base", 0, 1000, "min_cost", "base");
                addRow("Lowest cost: per level", 0, 1000, "min_cost", "per_level_above_first");
                addRow("Highest cost: base", 0, 1000, "max_cost", "base");
                addRow("Highest cost: per level", 0, 1000, "max_cost", "per_level_above_first");
                status = "";
                this.rebuildWidgets();
            } catch (Exception e) {
                status = "Could not read this enchantment: " + e.getMessage();
            }
        } else if (kind.equals("DATA_ERROR")) {
            status = data;
        }
    }

    @Override
    protected void addExtraWidgets(int y) {
        if (json == null || !json.has("supported_items")) {
            return;
        }
        this.addRenderableWidget(Button.builder(Component.literal("Change"), b -> {
            List<String> tags = new ArrayList<>(TagForgeScreen.tagNames(BuiltInRegistries.ITEM));
            this.minecraft.setScreen(new IdPickerScreen(this, "Items it applies to", tags, tag -> {
                json.addProperty("supported_items", "#" + tag);
                if (json.has("primary_items")) {
                    json.addProperty("primary_items", "#" + tag);
                }
            }));
        }).bounds(left + 230, y, 70, 18)
                .tooltip(Tooltip.create(Component.literal("Choose a tag of items this enchantment can go on."))).build());
    }

    @Override
    protected void drawExtra(GuiGraphics g, int y) {
        if (json != null && json.has("supported_items")) {
            g.drawString(this.font, "Items it applies to:", left + 4, y + 5, 0xFFFFFF);
            g.drawString(this.font, this.font.plainSubstrByWidth(json.get("supported_items").toString().replace("\"", ""), 150),
                    left + 100, y + 5, 0xAAAAAA);
        }
    }

    @Override
    protected void sendSave() {
        if (json == null) {
            return;
        }
        String text = GSON.toJson(json);
        if (text.length() > 29000) {
            status = "This enchantment is too big to send from here.";
            return;
        }
        PacketDistributor.sendToServer(new RecipeEditPayload("ENCH_SET", id, text));
        status = "Saving...";
    }

    @Override
    protected void sendRestore() {
        PacketDistributor.sendToServer(new RecipeEditPayload("ENCH_RESTORE", id, ""));
        status = "Putting the original back...";
    }
}
