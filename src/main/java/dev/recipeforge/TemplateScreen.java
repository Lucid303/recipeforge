package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;

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
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;

/** Lists every block used inside one structure file, with a button to replace all of one block with another. */
public class TemplateScreen extends ForgeScreen {
    private static final int ROW = 22;

    private record Row(String name, int count) {}

    private final String templateId;
    private final List<Row> rows = new ArrayList<>();
    private String sizeText = "";
    private boolean requested = false;
    private int seenVersion;
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private final int top = 34;

    public TemplateScreen(Screen parent, String templateId) {
        super(Component.literal("Structure blocks"), parent);
        this.templateId = templateId;
        this.seenVersion = ClientState.stationVersion;
        this.status = "Loading...";
    }

    private void request() {
        PacketDistributor.sendToServer(new RecipeEditPayload("TEMPLATE_GET", templateId, ""));
    }

    @Override
    protected void init() {
        if (!requested) {
            requested = true;
            request();
        }
        boxWidth = Math.min(this.width - 20, 460);
        left = (this.width - boxWidth) / 2;
        perPage = Math.max(1, (this.height - top - 62) / ROW);
        page = Math.max(0, Math.min(page, (rows.size() - 1) / perPage));

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= rows.size()) {
                break;
            }
            final Row row = rows.get(index);
            this.addRenderableWidget(Button.builder(Component.literal("Replace all"), b ->
                    this.minecraft.setScreen(new ItemPickerScreen(this, "Replace with", true, to -> {
                        JsonObject o = new JsonObject();
                        o.addProperty("from", row.name());
                        o.addProperty("to", to);
                        PacketDistributor.sendToServer(new RecipeEditPayload("TEMPLATE_REPLACE", templateId, o.toString()));
                        status = "Replacing...";
                    }))).bounds(left + boxWidth - 90, top + i * ROW + 1, 88, 18).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Put back the original"), b -> {
            PacketDistributor.sendToServer(new RecipeEditPayload("TEMPLATE_RESTORE", templateId, ""));
            status = "Putting the original back...";
        }).bounds(left, this.height - 28, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(left + boxWidth - 70, this.height - 28, 70, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(left + boxWidth / 2 - 50, this.height - 50, 26, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(left + boxWidth / 2 + 24, this.height - 50, 26, 16).build());
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
            request();
        }
    }

    @Override
    public void onData(String kind, String id, String data) {
        if (!id.equals(templateId)) {
            return;
        }
        if (kind.equals("TEMPLATE")) {
            rows.clear();
            try {
                JsonObject o = JsonParser.parseString(data).getAsJsonObject();
                StringBuilder size = new StringBuilder();
                for (JsonElement e : o.getAsJsonArray("size")) {
                    size.append(size.length() == 0 ? "" : " x ").append(e.getAsInt());
                }
                sizeText = size.toString();
                for (JsonElement e : o.getAsJsonArray("palette")) {
                    JsonObject p = e.getAsJsonObject();
                    rows.add(new Row(p.get("name").getAsString(), p.get("count").getAsInt()));
                }
                status = "Press \"Replace all\" next to a block to swap it everywhere in this structure.";
            } catch (Exception ex) {
                status = "Could not read this structure: " + ex.getMessage();
            }
            this.rebuildWidgets();
        } else if (kind.equals("TEMPLATE_ERROR")) {
            status = data;
        }
    }

    private static ItemStack iconOf(String blockId) {
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        Block block = BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
        return block == null ? ItemStack.EMPTY : new ItemStack(block.asItem());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, StationInfo.structure(), templateId);
        g.drawString(this.font, "Size: " + sizeText + " blocks", left, 24, 0xAAAAAA);
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= rows.size()) {
                break;
            }
            Row row = rows.get(index);
            int y = top + i * ROW;
            ItemStack icon = iconOf(row.name());
            if (!icon.isEmpty()) {
                g.renderItem(icon, left + 2, y + 3);
            }
            g.drawString(this.font, this.font.plainSubstrByWidth(row.name(), boxWidth - 200), left + 24, y + 7, 0xFFFFFF);
            g.drawString(this.font, row.count() + " blocks", left + boxWidth - 170, y + 7, 0xAAAAAA);
        }
        g.drawCenteredString(this.font, "Page " + (page + 1) + " / "
                + (Math.max(0, (rows.size() - 1) / perPage) + 1), left + boxWidth / 2, this.height - 46, 0xAAAAAA);
        drawStatus(g, left, this.height - 62, boxWidth);
    }
}
