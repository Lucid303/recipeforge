package dev.recipeforge.extras;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.recipeforge.RecipeEditPayload;
import dev.recipeforge.StationInfo;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/** Edits one world generation feature (for example an ore): how many per chunk, how rare, how high, how big. */
public class GenScreen extends NumberRowsScreen {
    static final StationInfo.Info INFO = new StationInfo.Info("World Gen Forge", Items.IRON_ORE, 0xFF6B5B3A);
    private static final Gson GSON = new GsonBuilder().create();

    private final String id;
    private JsonObject placed = null;
    private JsonObject configured = null;
    private String configuredId = null;

    public GenScreen(Screen parent, String id) {
        super(parent, INFO, id);
        this.id = id;
    }

    @Override
    protected void requestData() {
        PacketDistributor.sendToServer(new RecipeEditPayload("GEN_GET", id, ""));
    }

    private void numberOn(JsonObject o, String key, String label, int min, int max) {
        if (o != null && o.has(key) && o.get(key).isJsonPrimitive()) {
            numberRows.add(new Row(label, () -> o.get(key).getAsInt(), v -> o.addProperty(key, v), min, max));
        }
    }

    /** A height like {"absolute": -64}: edit the number inside, whatever it is called. */
    private void heightOn(JsonObject height, String side, String label) {
        if (height == null || !height.has(side) || !height.get(side).isJsonObject()) {
            return;
        }
        JsonObject inner = height.getAsJsonObject(side);
        if (inner.entrySet().isEmpty()) {
            return;
        }
        final String key = inner.entrySet().iterator().next().getKey();
        numberRows.add(new Row(label + " (" + key.replace('_', ' ') + ")", () -> inner.get(key).getAsInt(),
                v -> inner.addProperty(key, v), -2048, 2048));
    }

    @Override
    public void onData(String kind, String dataId, String data) {
        if (!dataId.equals(id)) {
            return;
        }
        if (kind.equals("GEN")) {
            try {
                JsonObject reply = JsonParser.parseString(data).getAsJsonObject();
                placed = reply.getAsJsonObject("placed");
                configured = reply.has("configured") ? reply.getAsJsonObject("configured") : null;
                configuredId = reply.has("configuredId") ? reply.get("configuredId").getAsString() : null;
                numberRows.clear();
                if (placed.has("placement")) {
                    JsonArray placement = placed.getAsJsonArray("placement");
                    for (JsonElement e : placement) {
                        if (!e.isJsonObject() || !e.getAsJsonObject().has("type")) {
                            continue;
                        }
                        JsonObject m = e.getAsJsonObject();
                        String type = m.get("type").getAsString();
                        switch (type) {
                            case "minecraft:count" -> {
                                if (m.has("count") && m.get("count").isJsonObject()) {
                                    JsonObject c = m.getAsJsonObject("count");
                                    numberOn(c, "value", "How many per chunk", 0, 1024);
                                    numberOn(c, "min_inclusive", "Fewest per chunk", 0, 1024);
                                    numberOn(c, "max_inclusive", "Most per chunk", 0, 1024);
                                } else {
                                    numberOn(m, "count", "How many per chunk", 0, 1024);
                                }
                            }
                            case "minecraft:rarity_filter" -> numberOn(m, "chance", "Rarity: 1 in this many chunks", 1, 1024);
                            case "minecraft:height_range" -> {
                                JsonObject h = m.has("height") && m.get("height").isJsonObject() ? m.getAsJsonObject("height") : null;
                                heightOn(h, "min_inclusive", "Lowest level");
                                heightOn(h, "max_inclusive", "Highest level");
                            }
                            default -> { }
                        }
                    }
                }
                if (configured != null && configured.has("config") && configured.get("config").isJsonObject()) {
                    numberOn(configured.getAsJsonObject("config"), "size", "Vein size", 0, 64);
                }
                status = numberRows.isEmpty() ? "Nothing in this feature can be changed with these buttons." : "";
                this.rebuildWidgets();
            } catch (Exception e) {
                status = "Could not read this feature: " + e.getMessage();
            }
        } else if (kind.equals("DATA_ERROR")) {
            status = data;
        }
    }

    @Override
    protected void sendSave() {
        if (placed == null) {
            return;
        }
        JsonObject req = new JsonObject();
        req.add("placed", placed);
        if (configured != null && configuredId != null) {
            req.addProperty("configuredId", configuredId);
            req.add("configured", configured);
        }
        String text = GSON.toJson(req);
        if (text.length() > 29000) {
            status = "This feature is too big to send from here.";
            return;
        }
        PacketDistributor.sendToServer(new RecipeEditPayload("GEN_SET", id, text));
        status = "Saving...";
    }

    @Override
    protected void sendRestore() {
        PacketDistributor.sendToServer(new RecipeEditPayload("GEN_RESTORE", id, ""));
        status = "Putting the original back...";
    }
}
