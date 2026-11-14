package dev.recipeforge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;

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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Every change made with any forge. Filter by forge at the top, pick a change on the left to see a
 * before / after picture, right-click a change to jump to that item / table / biome.
 */
public class HistoryScreen extends ForgeScreen {
    private static final int ROW = 22;

    private record Row(long seq, String time, String player, String action, String module, String id) {}

    private static final String[] FILTER_KEYS = {"recipe", "loot", "structure", "biome", "tag", "enchantment", "worldgen"};
    private static final String[] FILTER_LABELS = {"Recipe", "Loot", "Struct.", "Biome", "Tags", "Enchant", "WorldGen"};

    private final List<Row> rows = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private final Set<String> filter = new TreeSet<>();
    private final Map<String, Button> filterButtons = new LinkedHashMap<>();
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int listW;
    private int panelX;
    private int panelW;
    private final int top = 46;
    private boolean requested = false;
    private int seenVersion;

    private Row selectedRow = null;
    private String dBefore = "";
    private String dAfter = "";
    private String dModule = "";
    private String dAction = "";
    private long pendingOpenSeq = -1;

    public HistoryScreen(Screen parent) {
        super(Component.literal("History"), parent);
        this.seenVersion = ClientState.stationVersion;
    }

    private void requestList() {
        PacketDistributor.sendToServer(new RecipeEditPayload("GET_HISTORY", "", ""));
    }

    private void requestDetail(Row row) {
        dBefore = "";
        dAfter = "";
        PacketDistributor.sendToServer(new RecipeEditPayload("GET_HISTORY_ENTRY", Long.toString(row.seq()), ""));
    }

    private void select(Row row) {
        selectedRow = row;
        requestDetail(row);
        this.rebuildWidgets();
    }

    private static String filterKey(Row r) {
        return r.module().equals("station") ? "recipe" : r.module();
    }

    private static String shortVerb(String action) {
        return switch (action) {
            case "SAVE", "LOOT_SAVE", "STATION_SAVE", "TAG_EDIT", "ENCH_SET", "GEN_SET" -> "changed";
            case "DISABLE", "LOOT_DISABLE", "STATION_REMOVE" -> "removed";
            case "RESTORE", "LOOT_RESTORE", "STRUCT_RESTORE", "BIOME_RESET", "LOOT_ENABLE",
                 "TAG_RESTORE", "ENCH_RESTORE", "GEN_RESTORE" -> "undid";
            case "BIOME_SET" -> "colour";
            case "STRUCT_SET_BIOMES" -> "biomes";
            default -> action.toLowerCase();
        };
    }

    /** The action that puts the original back for a history entry, or null. */
    private static String undoAction(Row r) {
        return switch (r.module()) {
            case "recipe" -> "RESTORE";
            case "loot" -> "LOOT_RESTORE";
            case "biome" -> "BIOME_RESET";
            case "structure" -> "STRUCT_RESTORE";
            case "tag" -> "TAG_RESTORE";
            case "enchantment" -> "ENCH_RESTORE";
            case "worldgen" -> "GEN_RESTORE";
            default -> null;
        };
    }

    private static String undoId(Row r) {
        if (r.module().equals("biome")) {
            int paren = r.id().indexOf(" (");
            return paren > 0 ? r.id().substring(0, paren) : "";
        }
        return r.id();
    }

    @Override
    protected void init() {
        if (!requested) {
            requested = true;
            requestList();
        }
        forgetDynamic();
        filterButtons.clear();
        left = 8;
        listW = Math.max(150, Math.min(210, this.width / 2 - 14));
        panelX = left + listW + 8;
        panelW = this.width - panelX - 8;
        perPage = Math.max(1, (this.height - top - 40) / ROW);

        // filter row
        int total = FILTER_KEYS.length + 1;
        int gap = 3;
        int bw = Math.max(40, (this.width - 16 - gap * (total - 1)) / total);
        int fx = 8;
        Button all = Button.builder(Component.literal(filter.isEmpty() ? "[All]" : "All"), b -> {
            filter.clear();
            filtersChanged();
        }).bounds(fx, 24, bw, 16).tooltip(Tooltip.create(Component.literal("Show every forge."))).build();
        this.addRenderableWidget(all);
        filterButtons.put("all", all);
        for (int i = 0; i < FILTER_KEYS.length; i++) {
            final String key = FILTER_KEYS[i];
            Button b = Button.builder(Component.literal(filter.contains(key) ? "[" + FILTER_LABELS[i] + "]" : FILTER_LABELS[i]),
                    x -> {
                        if (!filter.add(key)) {
                            filter.remove(key);
                        }
                        filtersChanged();
                    }).bounds(fx + (i + 1) * (bw + gap), 24, bw, 16)
                    .tooltip(Tooltip.create(Component.literal("Show or hide this forge. You can pick several."))).build();
            this.addRenderableWidget(b);
            filterButtons.put(key, b);
        }

        applyFilter();
        buildList();

        if (selectedRow != null) {
            int by = this.height - 52;
            String undo = undoAction(selectedRow);
            boolean recipeRow = selectedRow.module().equals("recipe")
                    && (selectedRow.action().equals("SAVE") || selectedRow.action().equals("DISABLE")
                    || selectedRow.action().equals("RESTORE"));
            if (undo != null) {
                this.addRenderableWidget(Button.builder(Component.literal("Undo"), b ->
                        PacketDistributor.sendToServer(new RecipeEditPayload(undo, undoId(selectedRow), "")))
                        .bounds(panelX, by, 60, 20)
                        .tooltip(Tooltip.create(Component.literal("Put the original back."))).build());
            }
            if (recipeRow) {
                this.addRenderableWidget(Button.builder(Component.literal("Redo"), b ->
                        PacketDistributor.sendToServer(new RecipeEditPayload("REAPPLY", "", Long.toString(selectedRow.seq()))))
                        .bounds(panelX + 64, by, 60, 20)
                        .tooltip(Tooltip.create(Component.literal("Do this change again."))).build());
            }
        }

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width - 78, this.height - 28, 70, 20).build());
        addPager(left + listW / 2, this.height - 28, () -> changePage(-1), () -> changePage(1));
    }

    private void filtersChanged() {
        page = 0;
        filterButtons.get("all").setMessage(Component.literal(filter.isEmpty() ? "[All]" : "All"));
        for (int i = 0; i < FILTER_KEYS.length; i++) {
            filterButtons.get(FILTER_KEYS[i]).setMessage(Component.literal(
                    filter.contains(FILTER_KEYS[i]) ? "[" + FILTER_LABELS[i] + "]" : FILTER_LABELS[i]));
        }
        applyFilter();
        buildList();
    }

    private void applyFilter() {
        shown.clear();
        for (Row r : rows) {
            if (filter.isEmpty() || filter.contains(filterKey(r))) {
                shown.add(r);
            }
        }
    }

    private void buildList() {
        clearDynamic();
        page = Math.max(0, Math.min(page, (shown.size() - 1) / perPage));
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= shown.size()) {
                break;
            }
            final Row row = shown.get(index);
            String label = "#" + row.seq() + " " + row.module() + " " + shortVerb(row.action()) + " " + row.id();
            addDynamic(Button.builder(Component.literal(this.font.plainSubstrByWidth(label, listW - 8)),
                    b -> select(row)).bounds(left, top + i * ROW, listW, ROW - 2).build());
        }
    }

    private void changePage(int delta) {
        int max = Math.max(0, (shown.size() - 1) / perPage);
        int newPage = Math.max(0, Math.min(max, page + delta));
        if (newPage != page) {
            page = newPage;
            buildList();
        }
    }

    @Override
    protected void scrollPage(int delta) {
        changePage(delta);
    }

    @Override
    protected void onForgeTick() {
        if (seenVersion != ClientState.stationVersion) {
            seenVersion = ClientState.stationVersion;
            requestList();
        }
    }

    // ------------------------------------------------------------ right-click: jump to the thing that was changed

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && Ui.inside(mouseX, mouseY, left, top, listW, perPage * ROW)) {
            int index = page * perPage + (int) ((mouseY - top) / ROW);
            if (index >= 0 && index < shown.size()) {
                jumpTo(shown.get(index));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void jumpTo(Row row) {
        if (this.minecraft == null) {
            return;
        }
        try {
            switch (row.module()) {
                case "loot" -> this.minecraft.setScreen(new LootTableScreen(this, row.id()));
                case "structure" -> this.minecraft.setScreen(new StructureBiomesScreen(this, row.id()));
                case "biome" -> this.minecraft.setScreen(new BiomeForgeScreen(this,
                        ResourceLocation.parse(undoId(row))));
                case "recipe", "station" -> {
                    pendingOpenSeq = row.seq();
                    selectedRow = row;
                    requestDetail(row);
                }
                default -> {
                    BiFunction<Screen, String, Screen> opener = ExtensionsClient.historyOpener(row.module());
                    if (opener != null) {
                        this.minecraft.setScreen(opener.apply(this, row.id()));
                    }
                }
            }
        } catch (Exception e) {
            status = "Could not open that: " + e.getMessage();
        }
    }

    private static ResourceLocation itemKeyFrom(String module, String text) {
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            String id = null;
            if (module.equals("station")) {
                id = o.has("result") && !o.get("result").getAsString().isEmpty()
                        ? o.get("result").getAsString() : o.get("left").getAsString();
            } else if (o.has("result")) {
                JsonElement r = o.get("result");
                id = r.isJsonObject() ? r.getAsJsonObject().get("id").getAsString() : r.getAsString();
            }
            return id == null ? null : ResourceLocation.tryParse(id);
        } catch (Exception e) {
            return null;
        }
    }

    private void openItem(String module, String before, String after) {
        ResourceLocation key = itemKeyFrom(module, after.isEmpty() ? before : after);
        if (key == null) {
            key = itemKeyFrom(module, before);
        }
        Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        if (item == null) {
            status = "Could not find the item for that change.";
            return;
        }
        this.minecraft.setScreen(new RecipeListScreen(this, key, new ItemStack(item)));
    }

    // ------------------------------------------------------------ server replies

    @Override
    public void onHistory(String json) {
        rows.clear();
        try {
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement e : array) {
                JsonObject o = e.getAsJsonObject();
                rows.add(new Row(o.get("seq").getAsLong(), o.get("time").getAsString(), o.get("player").getAsString(),
                        o.get("action").getAsString(), o.has("module") ? o.get("module").getAsString() : "recipe",
                        o.get("id").getAsString()));
            }
            status = "";
        } catch (Exception ex) {
            status = "Could not read the history: " + ex.getMessage();
        }
        applyFilter();
        buildList();
    }

    @Override
    public void onData(String kind, String id, String data) {
        if (!kind.equals("HISTORY_ENTRY") || selectedRow == null || !id.equals(Long.toString(selectedRow.seq()))) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(data).getAsJsonObject();
            dModule = o.has("module") ? o.get("module").getAsString() : "";
            dAction = o.has("action") ? o.get("action").getAsString() : "";
            dBefore = o.has("before") ? o.get("before").getAsString() : "";
            dAfter = o.has("after") ? o.get("after").getAsString() : "";
            if (pendingOpenSeq == selectedRow.seq()) {
                pendingOpenSeq = -1;
                openItem(dModule, dBefore, dAfter);
            }
        } catch (Exception ex) {
            status = "Could not read this entry.";
        }
    }

    // ------------------------------------------------------------ drawing

    private void box(GuiGraphics g, int x, int y, int w, int h, String title, int color, String text) {
        g.fill(x, y, x + w, y + h, 0x40FFFFFF);
        Ui.outline(g, x, y, w, h, color);
        g.drawString(this.font, title, x + 4, y + 4, color);
        HistoryVisuals.draw(g, this.font, dModule, dAction, text, x + 6, y + 18, w - 12, h - 22);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, new StationInfo.Info("History", Items.WRITABLE_BOOK, 0xFF4A4A6A),
                "Right-click a change to open it");

        if (selectedRow != null) {
            g.drawString(this.font, this.font.plainSubstrByWidth(selectedRow.time() + "  " + selectedRow.player(), panelW),
                    panelX, top, 0xFFFFFF);
            g.drawString(this.font, this.font.plainSubstrByWidth(selectedRow.id(), panelW), panelX, top + 11, 0xAAAAAA);
            int by = top + 26;
            int bh = this.height - 52 - by - 6;
            boolean hasBefore = !dBefore.isEmpty();
            boolean hasAfter = !dAfter.isEmpty();
            if (hasBefore && hasAfter) {
                int half = (panelW - 6) / 2;
                box(g, panelX, by, half, bh, "BEFORE", 0xFFFFAA55, dBefore);
                box(g, panelX + half + 6, by, half, bh, "AFTER", 0xFF55FF55, dAfter);
            } else if (hasAfter) {
                box(g, panelX, by, panelW, bh, "NEW", 0xFF55FF55, dAfter);
            } else if (hasBefore) {
                boolean undone = dAction.contains("RESTORE") || dAction.contains("RESET") || dAction.contains("ENABLE");
                box(g, panelX, by, panelW, bh, undone ? "UNDONE (this is what was removed)" : "REMOVED (this is what it was)",
                        0xFFFF5555, dBefore);
            } else {
                g.drawString(this.font, "No picture for this change.", panelX, by + 6, 0x888888);
            }
        }
        drawPager(g, left + listW / 2, this.height - 28, page, (shown.size() - 1) / perPage + 1);
        drawStatus(g, panelX, this.height - 28, panelW - 90);
    }
}
