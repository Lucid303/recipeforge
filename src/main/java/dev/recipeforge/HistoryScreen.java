package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/** Every change made with any forge. Pick one on the left to see a before/after picture on the right. */
public class HistoryScreen extends ForgeScreen {
    private static final int ROW = 22;

    private record Row(long seq, String time, String player, String action, String module, String id) {}

    private final List<Row> rows = new ArrayList<>();
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int listW;
    private int panelX;
    private int panelW;
    private final int top = 30;
    private boolean requested = false;
    private int seenVersion;

    private Row selectedRow = null;
    private String dBefore = "";
    private String dAfter = "";
    private String dModule = "";
    private String dAction = "";
    private String dId = "";

    public HistoryScreen(Screen parent) {
        super(Component.literal("History"), parent);
        this.seenVersion = ClientState.stationVersion;
        this.status = "Loading history...";
    }

    private void requestList() {
        PacketDistributor.sendToServer(new RecipeEditPayload("GET_HISTORY", "", ""));
    }

    private void select(Row row) {
        selectedRow = row;
        dBefore = "";
        dAfter = "";
        PacketDistributor.sendToServer(new RecipeEditPayload("GET_HISTORY_ENTRY", Long.toString(row.seq()), ""));
        this.rebuildWidgets();
    }

    private static String shortVerb(String action) {
        return switch (action) {
            case "SAVE", "LOOT_SAVE", "STATION_SAVE" -> "changed";
            case "DISABLE", "LOOT_DISABLE", "STATION_REMOVE", "BREW_REMOVE" -> "removed";
            case "RESTORE", "LOOT_RESTORE", "STRUCT_RESTORE", "TEMPLATE_RESTORE", "BIOME_RESET", "LOOM_RESET" -> "undid";
            case "BIOME_SET" -> "colour";
            case "STRUCT_SET_BIOMES" -> "biomes";
            case "TEMPLATE_REPLACE" -> "replaced";
            case "LOOM_SET" -> "loom";
            default -> action.toLowerCase();
        };
    }

    /** The action that puts the original back for a history entry, or null. */
    private static String undoAction(Row r) {
        return switch (r.module()) {
            case "recipe" -> "RESTORE";
            case "loot" -> "LOOT_RESTORE";
            case "biome" -> "BIOME_RESET";
            case "loom" -> "LOOM_RESET";
            case "structure" -> r.action().startsWith("TEMPLATE") ? "TEMPLATE_RESTORE" : "STRUCT_RESTORE";
            default -> null;
        };
    }

    private static String undoId(Row r) {
        if (r.module().equals("biome")) {
            int paren = r.id().indexOf(" (");
            return paren > 0 ? r.id().substring(0, paren) : "";
        }
        if (r.module().equals("loom")) {
            return "";
        }
        return r.id();
    }

    @Override
    protected void init() {
        if (!requested) {
            requested = true;
            requestList();
        }
        left = 8;
        listW = Math.max(150, Math.min(210, this.width / 2 - 14));
        panelX = left + listW + 8;
        panelW = this.width - panelX - 8;
        perPage = Math.max(1, (this.height - top - 40) / ROW);
        page = Math.max(0, Math.min(page, (rows.size() - 1) / perPage));

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= rows.size()) {
                break;
            }
            final Row row = rows.get(index);
            String label = "#" + row.seq() + " " + row.module() + " " + shortVerb(row.action()) + " " + row.id();
            this.addRenderableWidget(Button.builder(
                    Component.literal(this.font.plainSubstrByWidth(label, listW - 8)), b -> select(row))
                    .bounds(left, top + i * ROW, listW, ROW - 2).build());
        }

        if (selectedRow != null) {
            int by = this.height - 52;
            String undo = undoAction(selectedRow);
            boolean recipeRow = selectedRow.module().equals("recipe");
            if (recipeRow) {
                this.addRenderableWidget(Button.builder(Component.literal("Use this version again"), b ->
                        PacketDistributor.sendToServer(new RecipeEditPayload("REAPPLY", "", Long.toString(selectedRow.seq()))))
                        .bounds(panelX, by, Math.min(140, panelW / 2 - 2), 20).build());
            }
            if (undo != null) {
                int ux = recipeRow ? panelX + Math.min(140, panelW / 2 - 2) + 4 : panelX;
                this.addRenderableWidget(Button.builder(Component.literal("Put back the original"), b ->
                        PacketDistributor.sendToServer(new RecipeEditPayload(undo, undoId(selectedRow), "")))
                        .bounds(ux, by, 130, 20).build());
            }
        }

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width - 78, this.height - 28, 70, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(left, this.height - 28, 26, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(left + listW - 26, this.height - 28, 26, 20).build());
    }

    private void changePage(int delta) {
        int max = Math.max(0, (rows.size() - 1) / perPage);
        int newPage = Math.max(0, Math.min(max, page + delta));
        if (newPage != page) {
            page = newPage;
            this.rebuildWidgets();
        }
    }

    @Override
    protected void onForgeTick() {
        if (seenVersion != ClientState.stationVersion) {
            seenVersion = ClientState.stationVersion;
            requestList();
        }
    }

    @Override
    public void onHistory(String json) {
        rows.clear();
        try {
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement e : array) {
                JsonObject o = e.getAsJsonObject();
                String action = o.get("action").getAsString();
                rows.add(new Row(o.get("seq").getAsLong(), o.get("time").getAsString(), o.get("player").getAsString(),
                        action, o.has("module") ? o.get("module").getAsString() : "recipe", o.get("id").getAsString()));
            }
            status = rows.isEmpty() ? "Nothing has been changed yet." : "Pick a change on the left.";
        } catch (Exception ex) {
            status = "Could not read the history: " + ex.getMessage();
        }
        this.rebuildWidgets();
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
            dId = o.has("id") ? o.get("id").getAsString() : "";
            dBefore = o.has("before") ? o.get("before").getAsString() : "";
            dAfter = o.has("after") ? o.get("after").getAsString() : "";
        } catch (Exception ex) {
            status = "Could not read this entry.";
        }
    }

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
                "Every change, before and after");

        if (selectedRow == null) {
            g.drawString(this.font, "Pick a change on the left to see what it did.", panelX, top + 6, 0xAAAAAA);
        } else {
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
                boolean undone = dAction.contains("RESTORE") || dAction.contains("RESET");
                box(g, panelX, by, panelW, bh, undone ? "UNDONE (this is what was removed)" : "REMOVED (this is what it was)",
                        0xFFFF5555, dBefore);
            } else {
                g.drawString(this.font, "No picture for this change.", panelX, by + 6, 0x888888);
            }
        }
        g.drawCenteredString(this.font, "Page " + (page + 1) + " / "
                + (Math.max(0, (rows.size() - 1) / perPage) + 1), left + listW / 2, this.height - 22, 0xAAAAAA);
        drawStatus(g, panelX, this.height - 28 - 2, panelW - 80);
    }
}
