package dev.recipeforge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * The picture editor for crafting table, furnace, blast furnace, smoker, campfire, stonecutter,
 * smithing table, anvil and grindstone. Click a square, then click an item on the right.
 */
public class GridEditorScreen extends ForgeScreen {
    private enum Layout { CRAFTING, COOKING, STONECUTTING, SMITHING, ANVIL, GRINDSTONE }

    private static final int STEP = 22;   // distance between squares
    private static final int BOX = 20;    // size of a square
    private static final int CELL = 18;   // size of a picker cell
    private static final int RESULT = 9;  // "selected" value meaning the result square
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String[] SMITHING_LABELS = {"Template", "Base item", "Addition"};
    private static List<ItemStack> allItems = null;
    private static Set<String> allNamespaces = null;

    private final JsonObject original;     // null when making something new
    private final String recipeId;         // null when making something new
    private final String typeId;
    private final Layout layout;
    private final JsonElement[] slotJson = new JsonElement[9];
    private final Ingredient[] slotIng = new Ingredient[9];
    private String resultItem = null;
    private ItemStack resultStack = ItemStack.EMPTY;
    private int resultCount = 1;
    private boolean shaped = true;
    private boolean blockMode = false;     // anvil / grindstone: block a combination instead of making an item
    private int cookTime;
    private float experience = 0.1f;
    private int levelCost = 5;             // anvil
    private int xpReward = 0;              // grindstone
    private int selected = 0;

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

    /** True for recipe types this screen can edit. */
    public static boolean supports(String type) {
        return layoutFor(type) != null;
    }

    private static Layout layoutFor(String type) {
        if (type == null) {
            return null;
        }
        if (type.startsWith("recipeforge:anvil")) {
            return Layout.ANVIL;
        }
        if (type.startsWith("recipeforge:grindstone")) {
            return Layout.GRINDSTONE;
        }
        return switch (type) {
            case "minecraft:crafting_shaped", "minecraft:crafting_shapeless" -> Layout.CRAFTING;
            case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking" ->
                    Layout.COOKING;
            case "minecraft:stonecutting" -> Layout.STONECUTTING;
            case "minecraft:smithing_transform" -> Layout.SMITHING;
            default -> null;
        };
    }

    private static int defaultCookTime(String type) {
        return switch (type) {
            case "minecraft:blasting", "minecraft:smoking" -> 100;
            case "minecraft:campfire_cooking" -> 600;
            default -> 200;
        };
    }

    public GridEditorScreen(Screen parent, JsonObject original, String recipeId, String presetResult, String newType) {
        super(Component.literal("Recipe Forge"), parent);
        this.original = original;
        this.recipeId = recipeId;
        String type = newType;
        if (original != null && original.has("type")) {
            type = original.get("type").getAsString();
        }
        if (layoutFor(type) == null) {
            type = "minecraft:crafting_shaped";
        }
        this.typeId = type;
        this.layout = layoutFor(type);
        this.shaped = !type.endsWith("shapeless");
        this.blockMode = type.endsWith("_block");
        this.cookTime = defaultCookTime(type);
        if (original != null) {
            loadFromJson(original);
        } else if (presetResult != null) {
            setResult(presetResult);
        }
    }

    // ------------------------------------------------------------ layout helpers

    private boolean isPair() {
        return layout == Layout.ANVIL || layout == Layout.GRINDSTONE;
    }

    private int slotCount() {
        return switch (layout) {
            case CRAFTING -> 9;
            case COOKING, STONECUTTING -> 1;
            case SMITHING -> 3;
            case ANVIL, GRINDSTONE -> 2;
        };
    }

    private int sx(int i) {
        return switch (layout) {
            case CRAFTING -> gridX + (i % 3) * STEP;
            case COOKING, STONECUTTING -> gridX + STEP;
            case SMITHING, ANVIL, GRINDSTONE -> gridX;
        };
    }

    private int sy(int i) {
        return switch (layout) {
            case CRAFTING -> gridY + (i / 3) * STEP;
            case COOKING, STONECUTTING -> gridY + STEP;
            case SMITHING, ANVIL, GRINDSTONE -> gridY + i * STEP;
        };
    }

    private String slotLabel(int i) {
        return switch (layout) {
            case SMITHING -> SMITHING_LABELS[i];
            case ANVIL -> i == 0 ? "First item" : "Second item";
            case GRINDSTONE -> i == 0 ? "Top item" : "Bottom item";
            default -> null;
        };
    }

    private boolean resultVisible() {
        return !(isPair() && blockMode);
    }

    private boolean showCount() {
        return layout == Layout.CRAFTING || layout == Layout.STONECUTTING || (isPair() && !blockMode);
    }

    private String currentType() {
        if (layout == Layout.CRAFTING) {
            return shaped ? "minecraft:crafting_shaped" : "minecraft:crafting_shapeless";
        }
        return typeId;
    }

    // ------------------------------------------------------------ loading

    private static JsonElement copyOf(JsonObject json, String key) {
        return json.has(key) ? json.get(key).deepCopy() : null;
    }

    private static String str(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    private static JsonElement itemIng(String id) {
        if (id == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("item", id);
        return o;
    }

    private void loadFromJson(JsonObject json) {
        try {
            switch (layout) {
                case CRAFTING -> {
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
                }
                case COOKING -> {
                    setSlot(0, copyOf(json, "ingredient"));
                    if (json.has("cookingtime")) {
                        cookTime = Math.max(1, json.get("cookingtime").getAsInt());
                    }
                    if (json.has("experience")) {
                        experience = json.get("experience").getAsFloat();
                    }
                }
                case STONECUTTING -> setSlot(0, copyOf(json, "ingredient"));
                case SMITHING -> {
                    setSlot(0, copyOf(json, "template"));
                    setSlot(1, copyOf(json, "base"));
                    setSlot(2, copyOf(json, "addition"));
                }
                case ANVIL, GRINDSTONE -> {
                    setSlot(0, itemIng(str(json, "left")));
                    setSlot(1, itemIng(str(json, "right")));
                    String res = str(json, "result");
                    if (res != null && !res.isEmpty()) {
                        setResult(res);
                    }
                    if (json.has("count")) {
                        resultCount = Math.max(1, Math.min(64, json.get("count").getAsInt()));
                    }
                    if (json.has("cost")) {
                        levelCost = Math.max(1, json.get("cost").getAsInt());
                    }
                    if (json.has("xp")) {
                        xpReward = Math.max(0, json.get("xp").getAsInt());
                    }
                    blockMode = "BLOCK".equals(str(json, "mode"));
                }
            }
            if (!isPair()) {
                JsonElement res = json.get("result");
                if (res != null && res.isJsonObject()) {
                    JsonObject o = res.getAsJsonObject();
                    setResult(o.get("id").getAsString());
                    if (o.has("count")) {
                        resultCount = Math.max(1, Math.min(64, o.get("count").getAsInt()));
                    }
                } else if (res != null && res.isJsonPrimitive()) {
                    setResult(res.getAsString());
                }
            }
        } catch (Exception e) {
            status = "Could not read this completely: " + e.getMessage();
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

    public static String keyOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static List<ItemStack> allItems() {
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

    public static Set<String> namespaces() {
        if (allNamespaces == null) {
            Set<String> set = new HashSet<>();
            for (ItemStack s : allItems()) {
                set.add(BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace());
            }
            allNamespaces = set;
        }
        return allNamespaces;
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
        searchBox.setHint(Component.literal("Search (@minecraft)"));
        searchBox.setValue(prev);
        searchBox.setResponder(text -> {
            page = 0;
            applyFilter(text);
        });
        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);

        int belowY = gridY + 3 * STEP + 6;
        shapeButton = null;
        if (layout == Layout.CRAFTING) {
            shapeButton = Button.builder(Component.literal(shaped ? "Shaped" : "Shapeless"), b -> toggleShape())
                    .bounds(gridX, belowY, 90, 20).build();
            this.addRenderableWidget(shapeButton);
        }
        if (layout == Layout.COOKING) {
            this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changeCook(-20))
                    .bounds(gridX + 96, belowY, 14, 14).build());
            this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changeCook(20))
                    .bounds(gridX + 112, belowY, 14, 14).build());
            this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changeXp(-0.1f))
                    .bounds(gridX + 96, belowY + 18, 14, 14).build());
            this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changeXp(0.1f))
                    .bounds(gridX + 112, belowY + 18, 14, 14).build());
        }
        if (isPair()) {
            this.addRenderableWidget(Button.builder(
                    Component.literal(blockMode ? "Mode: Block it" : "Mode: Make an item"), b -> {
                        blockMode = !blockMode;
                        this.rebuildWidgets();
                    }).bounds(gridX, belowY, 126, 20).build());
            if (!blockMode) {
                int step = layout == Layout.ANVIL ? 1 : 5;
                this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changePairNumber(-step))
                        .bounds(gridX + 96, belowY + 26, 14, 14).build());
                this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changePairNumber(step))
                        .bounds(gridX + 112, belowY + 26, 14, 14).build());
            }
        }
        if (showCount()) {
            this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changeCount(-1))
                    .bounds(resX - 10, resY + BOX + 4, 14, 14).build());
            this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changeCount(1))
                    .bounds(resX + BOX - 4, resY + BOX + 4, 14, 14).build());
        }

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
        SearchFilter f = SearchFilter.parse(lastSearch, namespaces());
        filtered.clear();
        for (ItemStack s : allItems()) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (f.matches(key, s.getHoverName().getString())) {
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

    private void changeCook(int delta) {
        cookTime = Math.max(20, Math.min(72000, cookTime + delta));
    }

    private void changeXp(float delta) {
        experience = Math.max(0f, Math.min(100f, Math.round((experience + delta) * 100f) / 100f));
    }

    private void changePairNumber(int delta) {
        if (layout == Layout.ANVIL) {
            levelCost = Math.max(1, Math.min(100, levelCost + delta));
        } else {
            xpReward = Math.max(0, Math.min(1000, xpReward + delta));
        }
    }

    // ------------------------------------------------------------ saving

    private String autoId() {
        String path = resultItem == null ? "recipe" : resultItem.replace(':', '_').replace('/', '_');
        return "worldforge:custom_" + path + "_" + (System.currentTimeMillis() / 1000L % 1000000L);
    }

    private void buildCrafting(JsonObject out) {
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
    }

    private JsonObject buildResult() {
        JsonObject res = new JsonObject();
        if (original != null && original.has("result") && original.get("result").isJsonObject()) {
            res = original.getAsJsonObject("result").deepCopy();
            String oldId = res.has("id") ? res.get("id").getAsString() : null;
            if (oldId == null || !oldId.equals(resultItem)) {
                res.remove("components");
            }
        }
        res.addProperty("id", resultItem);
        if (showCount()) {
            res.addProperty("count", resultCount);
        }
        return res;
    }

    private JsonObject buildJson() {
        JsonObject out = original != null ? original.deepCopy() : new JsonObject();
        out.addProperty("type", currentType());
        switch (layout) {
            case CRAFTING -> buildCrafting(out);
            case COOKING -> {
                out.add("ingredient", slotJson[0].deepCopy());
                out.addProperty("cookingtime", cookTime);
                out.addProperty("experience", experience);
                if (!out.has("category")) {
                    out.addProperty("category", "misc");
                }
            }
            case STONECUTTING -> out.add("ingredient", slotJson[0].deepCopy());
            case SMITHING -> {
                out.add("template", slotJson[0].deepCopy());
                out.add("base", slotJson[1].deepCopy());
                out.add("addition", slotJson[2].deepCopy());
            }
            default -> { }
        }
        out.add("result", buildResult());
        return out;
    }

    private static String itemOf(JsonElement slot) {
        try {
            return slot.getAsJsonObject().get("item").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private void saveStation() {
        if (slotJson[0] == null || slotJson[1] == null) {
            status = "Fill both squares on the left first.";
            return;
        }
        String left = itemOf(slotJson[0]);
        String right = itemOf(slotJson[1]);
        if (left == null || right == null) {
            status = "Only plain items can be used here.";
            return;
        }
        if (!blockMode && (resultItem == null || resultStack.isEmpty())) {
            status = "Click the square next to the arrow, then click the item this makes.";
            return;
        }
        JsonObject entry = new JsonObject();
        if (recipeId != null) {
            entry.addProperty("id", recipeId);
        }
        entry.addProperty("mode", blockMode ? "BLOCK" : "ADD");
        entry.addProperty("left", left);
        entry.addProperty("right", right);
        if (!blockMode) {
            entry.addProperty("result", resultItem);
            entry.addProperty("count", resultCount);
            entry.addProperty("cost", levelCost);
            entry.addProperty("xp", xpReward);
        }
        String station = layout == Layout.ANVIL ? "anvil" : "grindstone";
        PacketDistributor.sendToServer(new RecipeEditPayload("STATION_SAVE", station, GSON.toJson(entry)));
        status = "Saving...";
    }

    private void save() {
        if (isPair()) {
            saveStation();
            return;
        }
        if (resultItem == null || resultStack.isEmpty()) {
            status = "Click the square next to the arrow, then click the item this recipe makes.";
            return;
        }
        int filled = 0;
        for (int i = 0; i < slotCount(); i++) {
            if (slotJson[i] != null) {
                filled++;
            }
        }
        if (layout == Layout.CRAFTING && filled == 0) {
            status = "Put at least one item in the grid first.";
            return;
        }
        if (layout != Layout.CRAFTING && filled < slotCount()) {
            status = "Fill every square on the left first.";
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
        if (selected == RESULT) {
            setResult(key);
        } else {
            JsonObject ing = new JsonObject();
            ing.addProperty("item", key);
            setSlot(selected, ing);
            if (selected < slotCount() - 1) {
                selected++;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        for (int i = 0; i < slotCount(); i++) {
            if (Ui.inside(mouseX, mouseY, sx(i), sy(i), BOX, BOX)) {
                selected = i;
                if (button == 1) {
                    setSlot(i, null);
                }
                return true;
            }
        }
        if (resultVisible() && Ui.inside(mouseX, mouseY, resX, resY, BOX, BOX)) {
            selected = RESULT;
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

    private String cookText() {
        String time = cookTime % 20 == 0 ? (cookTime / 20) + " s"
                : String.format(Locale.ROOT, "%.1f s", cookTime / 20.0);
        return "Cook time: " + time;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        StationInfo.banner(g, this.font, this.width, StationInfo.forType(typeId),
                recipeId != null ? "Editing: " + recipeId : "New");
        g.drawString(this.font, "1) Click a square", gridX, 24, 0xFFFFFF);
        g.drawString(this.font, "2) Click an item ->", gridX, 35, 0xFFFFFF);
        g.drawString(this.font, "Right-click = empty it", gridX, 46, 0xAAAAAA);

        Component hoverText = null;

        for (int i = 0; i < slotCount(); i++) {
            int x = sx(i);
            int y = sy(i);
            Ui.slot(g, x, y, BOX);
            ItemStack shown = slotIng[i] == null ? ItemStack.EMPTY : Ui.cycle(slotIng[i].getItems());
            if (!shown.isEmpty()) {
                g.renderItem(shown, x + 2, y + 2);
            }
            if (selected == i) {
                Ui.outline(g, x - 1, y - 1, BOX + 2, BOX + 2, 0xFFFFFF55);
            }
            String label = slotLabel(i);
            if (label != null) {
                g.drawString(this.font, label, x + BOX + 4, y + 6, 0xAAAAAA);
            }
            if (Ui.inside(mouseX, mouseY, x, y, BOX, BOX) && slotJson[i] != null) {
                hoverText = describe(slotJson[i], shown);
            }
        }

        if (resultVisible()) {
            g.drawString(this.font, "->", gridX + 3 * STEP + 6, resY + 6, 0xFFFFFF);
            g.drawString(this.font, "Makes", resX - 2, resY - 11, 0xFFFFFF);
            Ui.slot(g, resX, resY, BOX);
            if (!resultStack.isEmpty()) {
                ItemStack withCount = resultStack.copyWithCount(showCount() ? resultCount : 1);
                g.renderItem(withCount, resX + 2, resY + 2);
                g.renderItemDecorations(this.font, withCount, resX + 2, resY + 2);
            }
            if (selected == RESULT) {
                Ui.outline(g, resX - 1, resY - 1, BOX + 2, BOX + 2, 0xFFFFFF55);
            }
            if (showCount()) {
                g.drawCenteredString(this.font, String.valueOf(resultCount), resX + BOX / 2, resY + BOX + 7, 0xFFFFFF);
            }
            if (Ui.inside(mouseX, mouseY, resX, resY, BOX, BOX) && !resultStack.isEmpty()) {
                hoverText = resultStack.getHoverName();
            }
        } else {
            g.drawString(this.font, "This combination", gridX + 3 * STEP - 6, resY, 0xFF5555);
            g.drawString(this.font, "will be BLOCKED", gridX + 3 * STEP - 6, resY + 11, 0xFF5555);
        }

        int belowY = gridY + 3 * STEP + 6;
        switch (layout) {
            case CRAFTING -> g.drawString(this.font, shaped ? "Shaped: exact layout" : "Shapeless: any order",
                    gridX, belowY + 24, 0xAAAAAA);
            case COOKING -> {
                g.drawString(this.font, cookText(), gridX, belowY + 3, 0xFFFFFF);
                g.drawString(this.font, "XP: " + String.format(Locale.ROOT, "%.2f", experience),
                        gridX, belowY + 21, 0xFFFFFF);
            }
            case STONECUTTING -> g.drawString(this.font, "1 item in, items out", gridX, belowY + 3, 0xAAAAAA);
            case SMITHING -> g.drawString(this.font, "All 3 squares are needed", gridX, belowY + 3, 0xAAAAAA);
            case ANVIL -> {
                if (!blockMode) {
                    g.drawString(this.font, "XP levels: " + levelCost, gridX, belowY + 29, 0xFFFFFF);
                }
            }
            case GRINDSTONE -> {
                if (!blockMode) {
                    g.drawString(this.font, "XP given: " + xpReward, gridX, belowY + 29, 0xFFFFFF);
                }
            }
        }

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

        g.drawString(this.font, recipeId != null ? "Saving replaces the existing one."
                : "Saving adds a new one.", gridX, this.height - 54, 0x888888);
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
