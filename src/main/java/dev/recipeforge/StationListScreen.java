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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** Lists the custom combinations for the anvil or the grindstone. */
public class StationListScreen extends ForgeScreen {
    private static final int ROW = 40;

    private final String station;           // "anvil" or "grindstone"
    private final List<JsonObject> entries = new ArrayList<>();
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private int top = 30;
    private boolean requested = false;
    private int seenVersion;
    private ItemStack hoverStack = ItemStack.EMPTY;

    public StationListScreen(Screen parent, String station) {
        super(Component.literal(station), parent);
        this.station = station;
        this.seenVersion = ClientState.stationVersion;
        this.status = "Loading...";
    }

    private String type() {
        return station.equals("anvil") ? "recipeforge:anvil" : "recipeforge:grindstone";
    }

    private void request() {
        PacketDistributor.sendToServer(new RecipeEditPayload("GET_STATION", station, ""));
    }

    @Override
    protected void init() {
        if (!requested) {
            requested = true;
            request();
        }
        boxWidth = Math.min(this.width - 20, 460);
        left = (this.width - boxWidth) / 2;
        perPage = Math.max(1, (this.height - top - 40) / ROW);
        page = Math.max(0, Math.min(page, (entries.size() - 1) / perPage));

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= entries.size()) {
                break;
            }
            final JsonObject entry = entries.get(index);
            int y = top + i * ROW;
            int bx = left + boxWidth - 128;
            this.addRenderableWidget(Button.builder(Component.literal("Edit"), b ->
                    this.minecraft.setScreen(new GridEditorScreen(this, entry,
                            entry.get("id").getAsString(), null, type())))
                    .bounds(bx, y + 6, 60, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
                PacketDistributor.sendToServer(new RecipeEditPayload("STATION_REMOVE", station,
                        entry.get("id").getAsString()));
                status = "Removing...";
            }).bounds(bx + 64, y + 6, 60, 20).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Add combination"), b ->
                this.minecraft.setScreen(new GridEditorScreen(this, null, null, null, type())))
                .bounds(left, this.height - 28, 110, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Block one"), b ->
                this.minecraft.setScreen(new GridEditorScreen(this, null, null, null, type() + "_block")))
                .bounds(left + 114, this.height - 28, 80, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(left + boxWidth - 70, this.height - 28, 70, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(left + 210, this.height - 28, 26, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(left + 290, this.height - 28, 26, 20).build());
    }

    private void changePage(int delta) {
        int max = Math.max(0, (entries.size() - 1) / perPage);
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
            request();
        }
    }

    @Override
    public void onStation(String id, String json) {
        if (!id.equals(station)) {
            return;
        }
        entries.clear();
        try {
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement e : array) {
                entries.add(e.getAsJsonObject());
            }
            status = entries.isEmpty() ? "Nothing added yet. Press \"Add combination\"." : "";
        } catch (Exception ex) {
            status = "Could not read the list: " + ex.getMessage();
        }
        this.rebuildWidgets();
    }

    private static ItemStack stackOf(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation rl = ResourceLocation.tryParse(o.get(key).getAsString());
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        return BuiltInRegistries.ITEM.getOptional(rl).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        hoverStack = ItemStack.EMPTY;
        StationInfo.banner(g, this.font, this.width, StationInfo.forType(type()), "Custom combinations");

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= entries.size()) {
                break;
            }
            JsonObject e = entries.get(index);
            int y = top + i * ROW;
            g.fill(left, y, left + boxWidth, y + ROW - 4, 0x40FFFFFF);
            boolean block = e.has("mode") && e.get("mode").getAsString().equals("BLOCK");

            ItemStack a = stackOf(e, "left");
            ItemStack b = stackOf(e, "right");
            drawIcon(g, a, left + 8, y + 9, mouseX, mouseY);
            g.drawString(this.font, "+", left + 28, y + 13, 0xFFFFFF);
            drawIcon(g, b, left + 38, y + 9, mouseX, mouseY);
            if (block) {
                g.drawString(this.font, "BLOCKED - cannot be combined", left + 66, y + 13, 0xFF5555);
            } else {
                g.drawString(this.font, "->", left + 60, y + 13, 0xFFFFFF);
                ItemStack r = stackOf(e, "result");
                int count = e.has("count") ? e.get("count").getAsInt() : 1;
                if (!r.isEmpty()) {
                    r = r.copyWithCount(count);
                }
                drawIcon(g, r, left + 78, y + 9, mouseX, mouseY);
                if (!r.isEmpty()) {
                    g.renderItemDecorations(this.font, r, left + 78, y + 9);
                }
                String extra = station.equals("anvil")
                        ? "Costs " + (e.has("cost") ? e.get("cost").getAsInt() : 1) + " levels"
                        : "Gives " + (e.has("xp") ? e.get("xp").getAsInt() : 0) + " XP";
                g.drawString(this.font, extra, left + 104, y + 13, 0xAAAAAA);
            }
        }

        g.drawCenteredString(this.font, "Page " + (page + 1) + " / "
                + (Math.max(0, (entries.size() - 1) / perPage) + 1), left + 263, this.height - 42, 0xAAAAAA);
        drawStatus(g, left, this.height - 54, boxWidth);
        if (!hoverStack.isEmpty()) {
            g.renderTooltip(this.font, hoverStack.getHoverName(), mouseX, mouseY);
        }
    }

    private void drawIcon(GuiGraphics g, ItemStack stack, int x, int y, int mouseX, int mouseY) {
        if (stack.isEmpty()) {
            return;
        }
        g.renderItem(stack, x, y);
        if (Ui.inside(mouseX, mouseY, x, y, 16, 16)) {
            hoverStack = stack;
        }
    }
}
