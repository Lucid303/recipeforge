package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/** Structure Forge main screen: choose a structure, then change where it spawns or which blocks it uses. */
public class StructureForgeScreen extends ForgeScreen {
    private static final int ROW = 20;

    private final List<String> structures = new ArrayList<>();
    private final List<String> templates = new ArrayList<>();
    private final List<String> filtered = new ArrayList<>();
    private boolean spawnMode = true;
    private EditBox searchBox;
    private String lastFilter = "";
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private final int top = 76;
    private boolean requested = false;

    public StructureForgeScreen(Screen parent) {
        super(Component.literal("Structure Forge"), parent);
        this.status = "Loading structures...";
    }

    @Override
    protected void init() {
        if (!requested) {
            requested = true;
            PacketDistributor.sendToServer(new RecipeEditPayload("STRUCT_LIST", "", ""));
        }
        String prev = searchBox != null ? searchBox.getValue() : lastFilter;
        boxWidth = Math.min(this.width - 20, 460);
        left = (this.width - boxWidth) / 2;
        perPage = Math.max(1, (this.height - top - 40) / ROW);

        this.addRenderableWidget(Button.builder(Component.literal(spawnMode ? "[Where they spawn]" : "Where they spawn"), b -> {
            spawnMode = true;
            page = 0;
            this.rebuildWidgets();
        }).bounds(left, 26, 140, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(!spawnMode ? "[Blocks inside]" : "Blocks inside"), b -> {
            spawnMode = false;
            page = 0;
            this.rebuildWidgets();
        }).bounds(left + 144, 26, 140, 20).build());

        searchBox = new EditBox(this.font, left, 50, 220, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search  (try @minecraft)"));
        searchBox.setValue(prev);
        searchBox.setResponder(t -> {
            page = 0;
            applyFilter(t);
            this.rebuildWidgets();
        });
        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);

        applyFilter(prev);
        page = Math.max(0, Math.min(page, (filtered.size() - 1) / perPage));
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= filtered.size()) {
                break;
            }
            final String id = filtered.get(index);
            this.addRenderableWidget(Button.builder(
                    Component.literal(this.font.plainSubstrByWidth(id, boxWidth - 10)), b -> {
                        if (spawnMode) {
                            this.minecraft.setScreen(new StructureBiomesScreen(this, id));
                        } else {
                            this.minecraft.setScreen(new TemplateScreen(this, id));
                        }
                    }).bounds(left, top + i * ROW, boxWidth, ROW - 2).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(left + boxWidth - 70, this.height - 28, 70, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(left + 150, this.height - 28, 26, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(left + 240, this.height - 28, 26, 20).build());
    }

    private void applyFilter(String text) {
        lastFilter = text == null ? "" : text;
        List<String> source = spawnMode ? structures : templates;
        List<String> namespaces = new ArrayList<>();
        for (String id : source) {
            String ns = id.substring(0, id.indexOf(':'));
            if (!namespaces.contains(ns)) {
                namespaces.add(ns);
            }
        }
        SearchFilter f = SearchFilter.parse(lastFilter, namespaces);
        filtered.clear();
        for (String id : source) {
            if (f.matches(ResourceLocation.parse(id), id.substring(id.indexOf(':') + 1).replace('_', ' '))) {
                filtered.add(id);
            }
        }
    }

    private void changePage(int delta) {
        int max = Math.max(0, (filtered.size() - 1) / perPage);
        int newPage = Math.max(0, Math.min(max, page + delta));
        if (newPage != page) {
            page = newPage;
            this.rebuildWidgets();
        }
    }

    @Override
    public void onData(String kind, String id, String data) {
        if (!kind.equals("STRUCT_LIST")) {
            return;
        }
        structures.clear();
        templates.clear();
        try {
            JsonObject o = JsonParser.parseString(data).getAsJsonObject();
            for (JsonElement e : o.getAsJsonArray("structures")) {
                structures.add(e.getAsString());
            }
            for (JsonElement e : o.getAsJsonArray("templates")) {
                templates.add(e.getAsString());
            }
            status = structures.size() + " structures, " + templates.size() + " structure files";
        } catch (Exception ex) {
            status = "Could not read the list: " + ex.getMessage();
        }
        this.rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, StationInfo.structure(),
                spawnMode ? "Which biomes a structure spawns in" : "Replace blocks inside a structure");
        g.drawCenteredString(this.font, "Page " + (page + 1) + " / "
                + (Math.max(0, (filtered.size() - 1) / perPage) + 1), left + 208, this.height - 42, 0xAAAAAA);
        drawStatus(g, left, this.height - 54, boxWidth);
    }
}
