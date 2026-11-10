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
import net.neoforged.neoforge.network.PacketDistributor;

/** A list of every change made, with buttons to use a version again or put the original back. */
public class HistoryScreen extends ForgeScreen {
    private static final int ROW = 36;

    private record Row(long seq, String time, String player, String action, String id) {}

    private final List<Row> rows = new ArrayList<>();
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private boolean requested = false;
    private int seenVersion;

    public HistoryScreen(Screen parent) {
        super(Component.literal("History of changes"), parent);
        this.seenVersion = ClientState.recipeVersion;
        this.status = "Loading history...";
    }

    private void requestHistory() {
        PacketDistributor.sendToServer(new RecipeEditPayload("GET_HISTORY", "", ""));
    }

    @Override
    protected void init() {
        if (!requested) {
            requested = true;
            requestHistory();
        }
        boxWidth = Math.min(this.width - 20, 460);
        left = (this.width - boxWidth) / 2;
        int top = 30;
        perPage = Math.max(1, (this.height - top - 56) / ROW);
        page = Math.max(0, Math.min(page, (rows.size() - 1) / perPage));

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= rows.size()) {
                break;
            }
            final Row row = rows.get(index);
            int y = top + i * ROW;
            this.addRenderableWidget(Button.builder(Component.literal("Use this version again"),
                    b -> PacketDistributor.sendToServer(
                            new RecipeEditPayload("REAPPLY", "", Long.toString(row.seq()))))
                    .bounds(left, y + 12, 150, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Put back the original"),
                    b -> PacketDistributor.sendToServer(new RecipeEditPayload("RESTORE", row.id(), "")))
                    .bounds(left + 156, y + 12, 150, 20).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(left + boxWidth - 70, this.height - 28, 70, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(this.width / 2 - 50, this.height - 28, 30, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(this.width / 2 + 20, this.height - 28, 30, 20).build());
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
        if (seenVersion != ClientState.recipeVersion) {
            seenVersion = ClientState.recipeVersion;
            requestHistory();
        }
    }

    @Override
    public void onHistory(String json) {
        rows.clear();
        try {
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement e : array) {
                JsonObject o = e.getAsJsonObject();
                rows.add(new Row(o.get("seq").getAsLong(), o.get("time").getAsString(),
                        o.get("player").getAsString(), o.get("action").getAsString(), o.get("id").getAsString()));
            }
            status = rows.isEmpty() ? "Nothing has been changed yet." : "";
        } catch (Exception ex) {
            status = "Could not read the history: " + ex.getMessage();
        }
        this.rebuildWidgets();
    }

    private static String verb(String action) {
        return switch (action) {
            case "SAVE" -> "changed";
            case "DISABLE" -> "removed";
            case "RESTORE" -> "put back the original of";
            default -> action;
        };
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        int top = 30;
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= rows.size()) {
                break;
            }
            Row row = rows.get(index);
            int y = top + i * ROW;
            String text = row.time() + "  " + row.player() + " " + verb(row.action()) + " " + row.id();
            g.drawString(this.font, this.font.plainSubstrByWidth(text, boxWidth), left, y, 0xFFFFFF);
        }
        g.drawCenteredString(this.font, "Page " + (page + 1) + " / "
                + (Math.max(0, (rows.size() - 1) / perPage) + 1), this.width / 2, this.height - 42, 0xAAAAAA);
        drawStatus(g, left, this.height - 54, boxWidth);
    }
}
