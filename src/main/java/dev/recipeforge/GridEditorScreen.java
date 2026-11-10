package dev.recipeforge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The 3x3 crafting grid editor. Click a square, click an item on the right.
 * Used both for editing an existing crafting recipe and for making a new one.
 */
public class GridEditorScreen extends ForgeScreen {
    private static final int STEP = 22;   // distance between grid squares
    private static final int BOX = 20;    // size of a grid square
    private static final int CELL = 18;   // size of a picker cell
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static List<ItemStack> allItems = null;

    private final JsonObject original;     // null when making a new recipe
    private final String recipeId;         // null when making a new recipe
    private final JsonElement[] slotJson = new JsonElement[9];
    private final Ingredient[] slotIng = new Ingredient[9];
    private String resultItem = null;
    private ItemStack resultStack = ItemStack.EMPTY;
    private int resultCount = 1;
    private boolean shaped = true;
    private int selected = 0;              // 0-8 grid squares, 9 = result square

    private EditBox searchBox;
    private Button shapeButton;
    private final List<ItemStack> filtered = new ArrayList<>();
    private int page = 0;
    private String lastSearch = "";
    private int gridX;
    private int gridY;
    private int resX;
    private int resY;
    private int pickX;
    private int pickY;
    private int pickCols;
    private int pickRows;

    public GridEditorScreen(Screen parent, JsonObject original, String recipeId, String presetResult) {
        super(Component.literal("Recipe Forge"), parent);
        this.original = original;
        this.recipeId = recipeId;
        if (original != null) {
            loadFromJson(original);
        } else if (presetResult != null) {
            setResult(presetResult);
        }
    }

    // ------------------------------------------------------------ loading

    private void loadFromJson(JsonObject json) {
        try {
            String type = json.get("type").getAsString();
            shaped = !type.endsWith("shapeless");
            if (shaped) {
                JsonArray pattern = json.getAsJsonArray("pattern");
                JsonObject key = json.getAsJsonObject("key");
                for (int r = 0; r < pattern.size() && r < 3; r++) {
                    String row = pattern.get(r).getAsString();
                    for (int c = 0; c < row.length() && c < 3; c++) {
                        char ch = row.charAt(c);
                        if (ch == ' ') {
                            continue;
                        }
                        JsonElement ing = key.get(String.valueOf(ch));
                        if (ing != null) {
                            setSlot(r * 3 + c, ing.deepCopy());
                        }
                    }
                }
            } else {
                JsonArray list = json.getAsJsonArray("ingredients");
                for (int i = 0; i < list.size() && i < 9; i++) {
                    setSlot(i, list.get(i).deepCopy());
                }
            }
            JsonObject res = json.getAsJsonObject("result");
            setResult(res.get("id").getAsString());
            if (res.has("count")) {
                resultCount = Math.max(1, Math.min(64, res.get("count").getAsInt()));
            }
        } catch (Exception e) {
            status = "Could not read this recipe completely: " + e.getMessage();
        }
    }

    private Ingredient parseIngredient(JsonElement json) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        RegistryOps<JsonElement> ops = mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        return Ingredient.CODEC.parse(ops, json).result().orElse(null);
    }

    private void setSlot(int index, JsonElement json) {
        slotJson[index] = json;
        slotIng[index] = json == null ? null : parseIngredient(json);
    }

    private void setResult(String id) {
        resultItem = id;
        resultStack = ItemStack.EMPTY;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null) {
            Optional<Item> item = BuiltInRegistries.ITEM.getOptional(rl);
            if (item.isPresent()) {
                resultStack = new ItemStack(item.get());
            }
        }
    }

    private static String keyOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static List<ItemStack> allItems() {
        if (allItems == null) {
            List<ItemStack> list = new ArrayList<>();
            BuiltInRegistries.ITEM.stream().forEach(item -> {
                if (item != Items.AIR) {
                    list.add(new ItemStack(item));
                }
            });
            list.sort((a, b) -> keyOf(a).compareTo(keyOf(b)));
            allItems = list;
        }
        return allItems;
    }

    // ------------------------------------------------------------ layout

    @Override
    protected void init() {
        String prev = searchBox != null ? searchBox.getValue() : lastSearch;

        pickCols = Math.max(5, Math.min(12, (this.width - 20 - 150) / CELL));
        int total = 138 + pickCols * CELL;
        gridX = Math.max(8, (this.width - total) / 2);
        gridY = 66;
        resX = gridX + 3 * STEP + 28;
        resY = gridY + STEP;
        pickX = gridX + 138;
        pickY = 50;
        pickRows = Math.max(2, (this.height - pickY - 36) / CELL);

        searchBox = new EditBox(this.font, pickX, 24, pickCols * CELL, 20, Component.literal("Search items"));
        searchBox.setMaxLength(100);
        searchBox.setHint(Component.literal("Search items..."));
        searchBox.setValue(prev);
        searchBox.setResponder(text -> {
            page = 0;
            applyFilter(text);
        });
        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);

        shapeButton = Button.builder(Component.literal(shaped ? "Shaped" : "Shapeless"), b -> toggleShape())
                .bounds(gridX, gridY + 3 * STEP + 6, 90, 20).build();
        this.addRenderableWidget(shapeButton);

        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changeCount(-1))
                .bounds(resX - 10, resY + BOX + 4, 14, 14).build());
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changeCount(1))
                .bounds(resX + BOX - 4, resY + BOX + 4, 14, 14).build());

        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(gridX, this.height - 28, 64, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
                .bounds(gridX + 68, this.height - 28, 64, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(pickX, this.height - 28, 20, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(pickX + pickCols * CELL - 20, this.height - 28, 20, 20).build());

        applyFilter(prev);
    }

    private void applyFilter(String text) {
        lastSearch = text == null ? "" : text;
        String f = lastSearch.trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (ItemStack s : allItems()) {
            if (f.isEmpty() || keyOf(s).contains(f)
                    || s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(f)) {
                filtered.add(s);
            }
        }
        page = Math.min(page, maxPage());
    }

    private int perPage() {
        return Math.max(1, pickCols * pickRows);
    }

    private int maxPage() {
        return Math.max(0, (filtered.size() - 1) / perPage());
    }

    private void changePage(int delta) {
        page = Math.max(0, Math.min(maxPage(), page + delta));
    }

    private void toggleShape() {
        shaped = !shaped;
        shapeButton.setMessage(Component.literal(shaped ? "Shaped" : "Shapeless"));
    }

    private void changeCount(int delta) {
        resultCount = Math.max(1, Math.min(64, resultCount + delta));
    }

    // ------------------------------------------------------------ saving

    private String autoId() {
        String path = resultItem == null ? "recipe" : resultItem.replace(':', '_').replace('/', '_');
        return "recipeforge:custom_" + path + "_" + (System.currentTimeMillis() / 1000L % 1000000L);
    }

    private JsonObject buildJson() {
        JsonObject out = original != null ? original.deepCopy() : new JsonObject();
        out.addProperty("type", shaped ? "minecraft:crafting_shaped" : "minecraft:crafting_shapeless");
        if (!out.has("category")) {
            out.addProperty("category", "misc");
        }
        out.remove("pattern");
        out.remove("key");
        out.remove("ingredients");

        if (shaped) {
            int minR = 3;
            int maxR = -1;
            int minC = 3;
            int maxC = -1;
            for (int i = 0; i < 9; i++) {
                if (slotJson[i] != null) {
                    minR = Math.min(minR, i / 3);
                    maxR = Math.max(maxR, i / 3);
                    minC = Math.min(minC, i % 3);
                    maxC = Math.max(maxC, i % 3);
                }
            }
            Map<String, Character> letters = new LinkedHashMap<>();
            JsonObject key = new JsonObject();
            JsonArray pattern = new JsonArray();
            char next = 'A';
            for (int r = minR; r <= maxR; r++) {
                StringBuilder row = new StringBuilder();
                for (int c = minC; c <= maxC; c++) {
                    JsonElement ing = slotJson[r * 3 + c];
                    if (ing == null) {
                        row.append(' ');
                    } else {
                        String asText = ing.toString();
                        Character letter = letters.get(asText);
                        if (letter == null) {
                            letter = next++;
                            letters.put(asText, letter);
                            key.add(String.valueOf(letter), ing.deepCopy());
                        }
                        row.append(letter.charValue());
                    }
                }
                pattern.add(row.toString());
            }
            out.add("pattern", pattern);
            out.add("key", key);
        } else {
            JsonArray list = new JsonArray();
            for (int i = 0; i < 9; i++) {
                if (slotJson[i] != null) {
                    list.add(slotJson[i].deepCopy());
                }
            }
            out.add("ingredients", list);
        }

        JsonObject res = new JsonObject();
        if (original != null && original.has("result") && original.get("result").isJsonObject()) {
            res = original.getAsJsonObject("result").deepCopy();
            String oldId = res.has("id") ? res.get("id").getAsString() : null;
            if (oldId == null || !oldId.equals(resultItem)) {
                res.remove("components");
            }
        }
        res.addProperty("id", resultItem);
        res.addProperty("count", resultCount);
        out.add("result", res);
        return out;
    }

    private void save() {
        if (resultItem == null || resultStack.isEmpty()) {
            status = "Click the square next to the arrow, then click the item this recipe makes.";
            return;
        }
        boolean any = false;
        for (JsonElement e : slotJson) {
            if (e != null) {
                any = true;
            }
        }
        if (!any) {
            status = "Put at least one item in the grid first.";
            return;
        }
        String id = recipeId != null ? recipeId : autoId();
        String json = GSON.toJson(buildJson());
        if (json.length() > 29000) {
            status = "This recipe is too big to send.";
            return;
        }
        PacketDistributor.sendToServer(new RecipeEditPayload("SAVE", id, json));
        status = "Saving...";
    }

    @Override
    public void onServerResult(boolean ok, String message) {
        status = message;
        if (ok && this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    // ------------------------------------------------------------ input

    private void pick(ItemStack stack) {
        String key = keyOf(stack);
        if (selected == 9) {
            setResult(key);
        } else {
            JsonObject ing = new JsonObject();
            ing.addProperty("item", key);
            setSlot(selected, ing);
            if (selected < 8) {
                selected++;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            int x = gridX + (i % 3) * STEP;
            int y = gridY + (i / 3) * STEP;
            if (Ui.inside(mouseX, mouseY, x, y, BOX, BOX)) {
                selected = i;
                if (button == 1) {
                    setSlot(i, null);
                }
                return true;
            }
        }
        if (Ui.inside(mouseX, mouseY, resX, resY, BOX, BOX)) {
            selected = 9;
            if (button == 1) {
                resultItem = null;
                resultStack = ItemStack.EMPTY;
            }
            return true;
        }
        if (button == 0) {
            int start = page * perPage();
            for (int i = 0; i < perPage() && start + i < filtered.size(); i++) {
                int x = pickX + (i % pickCols) * CELL;
                int y = pickY + (i / pickCols) * CELL;
                if (Ui.inside(mouseX, mouseY, x, y, CELL - 2, CELL - 2)) {
                    pick(filtered.get(start + i));
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            changePage(scrollY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        String head = recipeId != null ? "Editing: " + recipeId + "  (saving replaces it)"
                : "New recipe  (saving adds a new recipe)";
        g.drawCenteredString(this.font, this.font.plainSubstrByWidth(head, this.width - 20), this.width / 2, 8, 0xFFFFFF);
        g.drawString(this.font, "1) Click a square", gridX, 24, 0xFFFFFF);
        g.drawString(this.font, "2) Click an item ->", gridX, 35, 0xFFFFFF);
        g.drawString(this.font, "Right-click = empty it", gridX, 46, 0xAAAAAA);

        Component hoverText = null;

        for (int i = 0; i < 9; i++) {
            int x = gridX + (i % 3) * STEP;
            int y = gridY + (i / 3) * STEP;
            Ui.slot(g, x, y, BOX);
            ItemStack shown = slotIng[i] == null ? ItemStack.EMPTY : Ui.cycle(slotIng[i].getItems());
            if (!shown.isEmpty()) {
                g.renderItem(shown, x + 2, y + 2);
            }
            if (selected == i) {
                Ui.outline(g, x - 1, y - 1, BOX + 2, BOX + 2, 0xFFFFFF55);
            }
            if (Ui.inside(mouseX, mouseY, x, y, BOX, BOX) && slotJson[i] != null) {
                hoverText = describe(slotJson[i], shown);
            }
        }

        g.drawString(this.font, "->", gridX + 3 * STEP + 6, resY + 6, 0xFFFFFF);
        g.drawString(this.font, "Makes", resX - 2, resY - 11, 0xFFFFFF);
        Ui.slot(g, resX, resY, BOX);
        if (!resultStack.isEmpty()) {
            ItemStack withCount = resultStack.copyWithCount(resultCount);
            g.renderItem(withCount, resX + 2, resY + 2);
            g.renderItemDecorations(this.font, withCount, resX + 2, resY + 2);
        }
        if (selected == 9) {
            Ui.outline(g, resX - 1, resY - 1, BOX + 2, BOX + 2, 0xFFFFFF55);
        }
        g.drawCenteredString(this.font, String.valueOf(resultCount), resX + BOX / 2, resY + BOX + 7, 0xFFFFFF);
        if (Ui.inside(mouseX, mouseY, resX, resY, BOX, BOX) && !resultStack.isEmpty()) {
            hoverText = resultStack.getHoverName();
        }

        g.drawString(this.font, shaped ? "Shaped: exact layout" : "Shapeless: any order",
                gridX, gridY + 3 * STEP + 30, 0xAAAAAA);

        int start = page * perPage();
        for (int i = 0; i < perPage() && start + i < filtered.size(); i++) {
            ItemStack s = filtered.get(start + i);
            int x = pickX + (i % pickCols) * CELL;
            int y = pickY + (i / pickCols) * CELL;
            Ui.slot(g, x, y, CELL - 2);
            g.renderItem(s, x + 1, y + 1);
            if (Ui.inside(mouseX, mouseY, x, y, CELL - 2, CELL - 2)) {
                hoverText = s.getHoverName();
            }
        }
        g.drawCenteredString(this.font, "Page " + (page + 1) + " / " + (maxPage() + 1),
                pickX + pickCols * CELL / 2, this.height - 22, 0xAAAAAA);

        drawStatus(g, gridX, this.height - 42, this.width - 2 * gridX);
        if (hoverText != null) {
            g.renderTooltip(this.font, hoverText, mouseX, mouseY);
        }
    }

    private Component describe(JsonElement ingredient, ItemStack shown) {
        if (ingredient.isJsonObject() && ingredient.getAsJsonObject().has("tag")) {
            return Component.literal("Any item with tag " + ingredient.getAsJsonObject().get("tag").getAsString());
        }
        if (!shown.isEmpty()) {
            return shown.getHoverName();
        }
        return Component.literal("Special ingredient");
    }
}
