package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Edits one loot table: pools, items, weights and counts. Anything the editor does not understand
 * (conditions, special entries) is kept untouched. Press Save to apply.
 */
public class LootTableScreen extends ForgeScreen {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int ROW = 22;

    private record Row(int pool, int entry) {}   // entry == -1 means the pool header

    private final String tableId;
    private JsonObject table = null;
    private final List<Row> rows = new ArrayList<>();
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private final int top = 38;
    private boolean requested = false;
    private boolean unsaved = false;
    private int ticks = 0;
    private int requestAt = -1;
    private int seenVersion = 0;
    private boolean reloadPending = false;

    public LootTableScreen(Screen parent, String tableId) {
        super(Component.literal("Loot table"), parent);
        this.tableId = tableId;
        this.status = "Loading...";
    }

    // ------------------------------------------------------------ json helpers

    private JsonArray pools() {
        if (!table.has("pools") || !table.get("pools").isJsonArray()) {
            table.add("pools", new JsonArray());
        }
        return table.getAsJsonArray("pools");
    }

    private JsonObject pool(int p) {
        return pools().get(p).getAsJsonObject();
    }

    private JsonArray entries(int p) {
        JsonObject pool = pool(p);
        if (!pool.has("entries") || !pool.get("entries").isJsonArray()) {
            pool.add("entries", new JsonArray());
        }
        return pool.getAsJsonArray("entries");
    }

    private JsonObject entry(int p, int e) {
        return entries(p).get(e).getAsJsonObject();
    }

    private static int weightOf(JsonObject entry) {
        try {
            return entry.has("weight") ? entry.get("weight").getAsInt() : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private static JsonObject countFunction(JsonObject entry, boolean create) {
        if (!entry.has("functions") || !entry.get("functions").isJsonArray()) {
            if (!create) {
                return null;
            }
            entry.add("functions", new JsonArray());
        }
        JsonArray fns = entry.getAsJsonArray("functions");
        for (JsonElement e : fns) {
            if (e.isJsonObject() && e.getAsJsonObject().has("function")) {
                String f = e.getAsJsonObject().get("function").getAsString();
                if (f.equals("minecraft:set_count") || f.equals("set_count")) {
                    return e.getAsJsonObject();
                }
            }
        }
        if (!create) {
            return null;
        }
        JsonObject fn = new JsonObject();
        fn.addProperty("function", "minecraft:set_count");
        fn.addProperty("count", 1);
        fns.add(fn);
        return fn;
    }

    /** {min, max}, or {-1, -1} when the count is something this editor cannot show. */
    private static int[] countRange(JsonObject entry) {
        JsonObject fn = countFunction(entry, false);
        if (fn == null || !fn.has("count")) {
            return new int[] {1, 1};
        }
        try {
            JsonElement c = fn.get("count");
            if (c.isJsonPrimitive()) {
                int n = c.getAsInt();
                return new int[] {n, n};
            }
            JsonObject o = c.getAsJsonObject();
            return new int[] {o.get("min").getAsInt(), o.get("max").getAsInt()};
        } catch (Exception e) {
            return new int[] {-1, -1};
        }
    }

    private static void setCountRange(JsonObject entry, int min, int max) {
        min = Math.max(0, Math.min(64, min));
        max = Math.max(min, Math.min(64, max));
        JsonObject fn = countFunction(entry, true);
        if (min == max) {
            fn.addProperty("count", min);
        } else {
            JsonObject c = new JsonObject();
            c.addProperty("type", "minecraft:uniform");
            c.addProperty("min", min);
            c.addProperty("max", max);
            fn.add("count", c);
        }
    }

    private static ItemStack iconOf(JsonObject entry) {
        try {
            String type = entry.has("type") ? entry.get("type").getAsString() : "";
            if (type.endsWith("item") && entry.has("name")) {
                ResourceLocation rl = ResourceLocation.tryParse(entry.get("name").getAsString());
                if (rl != null) {
                    return BuiltInRegistries.ITEM.getOptional(rl).map(ItemStack::new).orElse(new ItemStack(Items.BARRIER));
                }
            }
            if (type.endsWith("tag")) {
                return new ItemStack(Items.NAME_TAG);
            }
            if (type.endsWith("loot_table")) {
                return new ItemStack(Items.CHEST);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return new ItemStack(Items.PAPER);
    }

    private static String nameOf(JsonObject entry) {
        try {
            String type = entry.has("type") ? entry.get("type").getAsString() : "?";
            if (type.endsWith("item") && entry.has("name")) {
                ItemStack s = iconOf(entry);
                return s.getItem() == Items.BARRIER ? entry.get("name").getAsString() : s.getHoverName().getString();
            }
            if (entry.has("name")) {
                return "(" + type.replace("minecraft:", "") + ") " + entry.get("name").getAsString();
            }
            return "(" + type.replace("minecraft:", "") + ")";
        } catch (Exception e) {
            return "(special entry)";
        }
    }

    private static Tooltip tip(String text) {
        return Tooltip.create(Component.literal(text));
    }

    private boolean isDisabled() {
        return table != null && table.has("worldforge_disabled");
    }

    private static String rollsText(JsonObject pool) {
        JsonElement r = pool.get("rolls");
        if (r == null || !r.isJsonPrimitive()) {
            return "(range)";
        }
        try {
            double d = r.getAsDouble();
            return d == Math.floor(d) ? Long.toString((long) d) : Double.toString(d);
        } catch (Exception e) {
            return "?";
        }
    }

    private void request() {
        PacketDistributor.sendToServer(new RecipeEditPayload("LOOT_GET", tableId, ""));
    }

    private void actionSent() {
        reloadPending = true;
        seenVersion = ClientState.recipeVersion;
    }

    // ------------------------------------------------------------ layout

    private void buildRows() {
        rows.clear();
        if (table == null) {
            return;
        }
        for (int p = 0; p < pools().size(); p++) {
            rows.add(new Row(p, -1));
            for (int e = 0; e < entries(p).size(); e++) {
                rows.add(new Row(p, e));
            }
        }
    }

    private void changed() {
        unsaved = true;
        buildRows();
        this.rebuildWidgets();
    }

    @Override
    protected void init() {
        if (!requested) {
            requested = true;
            request();
        }
        boxWidth = Math.min(this.width - 20, 460);
        left = (this.width - boxWidth) / 2;
        perPage = Math.max(1, (this.height - top - 84) / ROW);
        page = Math.max(0, Math.min(page, (rows.size() - 1) / perPage));

        if (table != null) {
            for (int i = 0; i < perPage; i++) {
                int index = page * perPage + i;
                if (index >= rows.size()) {
                    break;
                }
                final Row row = rows.get(index);
                int y = top + i * ROW;
                if (row.entry() < 0) {
                    addHeaderButtons(row.pool(), y);
                } else {
                    addEntryButtons(row.pool(), row.entry(), y);
                }
            }
        }

        int by = this.height - 28;
        this.addRenderableWidget(Button.builder(Component.literal("Add pool"), b -> bottomButton(0))
                .bounds(left, by, 70, 20).tooltip(tip("Add a new group of drops (a pool).")).build());
        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> bottomButton(1))
                .bounds(left + 74, by, 70, 20).tooltip(tip("Apply your changes to the game.")).build());
        this.addRenderableWidget(Button.builder(Component.literal(isDisabled() ? "Enable" : "Disable"), b -> bottomButton(2))
                .bounds(left + 148, by, 70, 20)
                .tooltip(tip(isDisabled() ? "Turn the drops back on." : "Turn this table off so it drops nothing.")).build());
        this.addRenderableWidget(Button.builder(Component.literal("Restore"), b -> bottomButton(3))
                .bounds(left + 222, by, 70, 20)
                .tooltip(tip("Throw away unsaved changes and put the original table back.")).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> bottomButton(4))
                .bounds(this.width - 78, by, 70, 20).build());
        addPager(this.width / 2, this.height - 52, () -> changePage(-1), () -> changePage(1));
    }

    private void addHeaderButtons(int p, int y) {
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changeRolls(p, -1))
                .bounds(left + 170, y + 2, 14, 16).tooltip(tip("Fewer rolls: how many times this pool picks an item.")).build());
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changeRolls(p, 1))
                .bounds(left + 186, y + 2, 14, 16).tooltip(tip("More rolls: how many times this pool picks an item.")).build());
        this.addRenderableWidget(Button.builder(Component.literal("Add item"), b ->
                this.minecraft.setScreen(new ItemPickerScreen(this, "Add an item", false, id -> {
                    JsonObject e = new JsonObject();
                    e.addProperty("type", "minecraft:item");
                    e.addProperty("name", id);
                    entries(p).add(e);
                    changed();
                }))).bounds(left + 210, y + 2, 64, 16).tooltip(tip("Add an item this pool can drop.")).build());
        this.addRenderableWidget(Button.builder(Component.literal("Remove pool"), b -> {
            pools().remove(p);
            changed();
        }).bounds(left + 278, y + 2, 80, 16).tooltip(tip("Delete this whole pool and all its items.")).build());
    }

    private void addEntryButtons(int p, int e, int y) {
        this.addRenderableWidget(Button.builder(Component.literal("Item"), b ->
                this.minecraft.setScreen(new ItemPickerScreen(this, "Pick the item", false, id -> {
                    JsonObject entry = entry(p, e);
                    entry.addProperty("type", "minecraft:item");
                    entry.addProperty("name", id);
                    changed();
                }))).bounds(left + 144, y + 2, 34, 16).tooltip(tip("Change which item drops.")).build());
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changeWeight(p, e, -1))
                .bounds(left + 210, y + 2, 14, 16).tooltip(tip("Less likely (lower weight).")).build());
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changeWeight(p, e, 1))
                .bounds(left + 226, y + 2, 14, 16).tooltip(tip("More likely (higher weight).")).build());
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changeCount(p, e, -1, 0))
                .bounds(left + 282, y + 2, 14, 16).tooltip(tip("Fewest items that can drop: lower.")).build());
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changeCount(p, e, 1, 0))
                .bounds(left + 298, y + 2, 14, 16).tooltip(tip("Fewest items that can drop: higher.")).build());
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changeCount(p, e, 0, -1))
                .bounds(left + 318, y + 2, 14, 16).tooltip(tip("Most items that can drop: lower.")).build());
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changeCount(p, e, 0, 1))
                .bounds(left + 334, y + 2, 14, 16).tooltip(tip("Most items that can drop: higher.")).build());
        this.addRenderableWidget(Button.builder(Component.literal("X"), b -> {
            entries(p).remove(e);
            changed();
        }).bounds(left + 356, y + 2, 18, 16).tooltip(tip("Remove this item from the drops.")).build());
    }

    private void changeRolls(int p, int delta) {
        JsonObject pool = pool(p);
        int cur = 1;
        try {
            if (pool.has("rolls") && pool.get("rolls").isJsonPrimitive()) {
                cur = (int) pool.get("rolls").getAsDouble();
            }
        } catch (Exception ignored) {
            cur = 1;
        }
        pool.addProperty("rolls", Math.max(0, Math.min(100, cur + delta)));
        changed();
    }

    private void changeWeight(int p, int e, int delta) {
        entry(p, e).addProperty("weight", Math.max(1, Math.min(1000, weightOf(entry(p, e)) + delta)));
        changed();
    }

    private void changeCount(int p, int e, int dMin, int dMax) {
        JsonObject entry = entry(p, e);
        int[] range = countRange(entry);
        if (range[0] < 0) {
            range = new int[] {1, 1};
        }
        int min = range[0] + dMin;
        int max = range[1] + dMax;
        if (dMin != 0 && min > max) {
            max = min;
        }
        if (dMax != 0 && max < min) {
            min = max;
        }
        setCountRange(entry, min, max);
        changed();
    }

    private void changePage(int delta) {
        int max = Math.max(0, (rows.size() - 1) / perPage);
        int newPage = Math.max(0, Math.min(max, page + delta));
        if (newPage != page) {
            page = newPage;
            this.rebuildWidgets();
        }
    }

    private void bottomButton(int which) {
        switch (which) {
            case 0 -> {
                if (table != null && !isDisabled()) {
                    JsonObject pool = new JsonObject();
                    pool.addProperty("rolls", 1);
                    pool.add("entries", new JsonArray());
                    pools().add(pool);
                    changed();
                }
            }
            case 1 -> save();
            case 2 -> {
                if (table == null) {
                    return;
                }
                PacketDistributor.sendToServer(new RecipeEditPayload(isDisabled() ? "LOOT_ENABLE" : "LOOT_DISABLE", tableId, ""));
                status = isDisabled() ? "Turning the drops back on..." : "Turning the table off...";
                actionSent();
            }
            case 3 -> {
                PacketDistributor.sendToServer(new RecipeEditPayload("LOOT_RESTORE", tableId, ""));
                status = "Putting the original back...";
                actionSent();
            }
            default -> this.onClose();
        }
    }

    private void save() {
        if (table == null) {
            return;
        }
        if (isDisabled()) {
            status = "This table is turned off. Press Enable first.";
            return;
        }
        String json = GSON.toJson(table);
        if (json.length() > 29000) {
            status = "This table is too big to send from here.";
            return;
        }
        PacketDistributor.sendToServer(new RecipeEditPayload("LOOT_SAVE", tableId, json));
        status = "Saving...";
        actionSent();
    }

    // ------------------------------------------------------------ server replies

    @Override
    public void onData(String kind, String id, String data) {
        if (!id.equals(tableId)) {
            return;
        }
        if (kind.equals("LOOT")) {
            try {
                table = JsonParser.parseString(data).getAsJsonObject();
                unsaved = false;
                status = "Change things, then press Save.";
                buildRows();
                this.rebuildWidgets();
            } catch (Exception e) {
                status = "Could not read this loot table: " + e.getMessage();
            }
        } else if (kind.equals("LOOT_ERROR")) {
            status = data;
        }
    }

    @Override
    public void onServerResult(boolean ok, String message) {
        status = message;
        if (ok) {
            requestAt = ticks + 20;   // look at the table again in about a second
        }
    }

    @Override
    protected void onForgeTick() {
        ticks++;
        if (requestAt >= 0 && ticks >= requestAt) {
            requestAt = -1;
            request();
        }
        if (reloadPending && seenVersion != ClientState.recipeVersion) {
            reloadPending = false;
            seenVersion = ClientState.recipeVersion;
            request();
        }
    }

    @Override
    protected void scrollPage(int delta) {
        changePage(delta);
    }

    @Override
    protected void drawUnderWidgets(GuiGraphics g, int mouseX, int mouseY) {
        if (table == null) {
            return;
        }
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= rows.size()) {
                break;
            }
            if (rows.get(index).entry() < 0) {
                g.fill(left, top + i * ROW, left + boxWidth, top + i * ROW + ROW - 2, 0x60FFFFFF);
            }
        }
    }

    // ------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, StationInfo.loot(), tableId);
        if (table == null) {
            g.drawCenteredString(this.font, status, this.width / 2, 60, 0xFFFFFF);
            return;
        }
        g.drawString(this.font, "W = how likely   x = how many drop   X = remove   (hover a button for help)",
                left, 25, 0x888888);
        if (isDisabled()) {
            g.drawCenteredString(this.font, "This loot table is turned off: it drops nothing.", this.width / 2, 70, 0xFF8888);
            g.drawCenteredString(this.font, "Press Enable to turn it back on.", this.width / 2, 84, 0xAAAAAA);
        }
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= rows.size()) {
                break;
            }
            Row row = rows.get(index);
            int y = top + i * ROW;
            if (row.entry() < 0) {
                g.drawString(this.font, "Pool " + (row.pool() + 1) + "   rolls: " + rollsText(pool(row.pool())),
                        left + 4, y + 7, 0xFFFFFF);
            } else {
                JsonObject entry = entry(row.pool(), row.entry());
                g.renderItem(iconOf(entry), left + 4, y + 2);
                g.drawString(this.font, this.font.plainSubstrByWidth(nameOf(entry), 116), left + 24, y + 7, 0xFFFFFF);
                g.drawString(this.font, "W:" + weightOf(entry), left + 182, y + 7, 0xAAAAAA);
                int[] c = countRange(entry);
                String count = c[0] < 0 ? "x?" : (c[0] == c[1] ? "x" + c[0] : "x" + c[0] + "-" + c[1]);
                g.drawString(this.font, count, left + 244, y + 7, 0xAAAAAA);
            }
        }
        if (rows.isEmpty() && !isDisabled()) {
            g.drawCenteredString(this.font, "This table drops nothing. Press \"Add pool\" to start.",
                    this.width / 2, 70, 0xAAAAAA);
        }
        drawPager(g, this.width / 2, this.height - 52, page, (rows.size() - 1) / perPage + 1);
        drawStatus(g, left, this.height - 78, boxWidth - 90);
        if (unsaved) {
            g.drawString(this.font, "* not saved yet", left + boxWidth - 80, this.height - 78, 0xFF8888);
        }
    }
}
