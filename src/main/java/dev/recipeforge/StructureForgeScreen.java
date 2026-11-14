package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;

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

/** Structure Forge: pick a structure, then choose which biomes it can spawn in. */
public class StructureForgeScreen extends ForgeScreen {
    private static final int ROW = 20;

    private final List<String> structures = new ArrayList<>();
    private final List<String> filtered = new ArrayList<>();
    private EditBox searchBox;
    private String lastFilter = "";
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private final int top = 54;
    private boolean requested = false;

    public StructureForgeScreen(Screen parent) {
        super(Component.literal("Structure Forge"), parent);
        this.status = "";
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
        perPage = Math.max(1, (this.height - top - 62) / ROW);

        forgetDynamic();
        searchBox = new EditBox(this.font, left, 26, 220, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search  (try @minecraft)"));
        searchBox.setValue(prev);
        searchBox.setResponder(t -> {
            page = 0;
            applyFilter(t);
            buildList();
        });
        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);

        applyFilter(prev);
        buildList();

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width - 78, this.height - 28, 70, 20).build());
        addPager(this.width / 2, this.height - 28, () -> changePage(-1), () -> changePage(1));
    }

    private void applyFilter(String text) {
        lastFilter = text == null ? "" : text;
        List<String> namespaces = new ArrayList<>();
        for (String id : structures) {
            String ns = id.substring(0, id.indexOf(':'));
            if (!namespaces.contains(ns)) {
                namespaces.add(ns);
            }
        }
        SearchFilter f = SearchFilter.parse(lastFilter, namespaces);
        filtered.clear();
        for (String id : structures) {
            if (f.matches(ResourceLocation.parse(id), id.substring(id.indexOf(':') + 1).replace('_', ' '))) {
                filtered.add(id);
            }
        }
    }

    private void buildList() {
        clearDynamic();
        page = Math.max(0, Math.min(page, (filtered.size() - 1) / perPage));
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= filtered.size()) {
                break;
            }
            final String id = filtered.get(index);
            addDynamic(Button.builder(
                    Component.literal(this.font.plainSubstrByWidth(id, boxWidth - 10)),
                    b -> this.minecraft.setScreen(new StructureBiomesScreen(this, id)))
                    .bounds(left, top + i * ROW, boxWidth, ROW - 2).build());
        }
    }

    private void changePage(int delta) {
        int max = Math.max(0, (filtered.size() - 1) / perPage);
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
    public void onData(String kind, String id, String data) {
        if (!kind.equals("STRUCT_LIST")) {
            return;
        }
        structures.clear();
        try {
            JsonObject o = JsonParser.parseString(data).getAsJsonObject();
            for (JsonElement e : o.getAsJsonArray("structures")) {
                structures.add(e.getAsString());
            }
            status = "";
        } catch (Exception ex) {
            status = "Could not read the list: " + ex.getMessage();
        }
        this.rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, StationInfo.structure(), "Which biomes a structure spawns in");
        drawPager(g, this.width / 2, this.height - 28, page, (filtered.size() - 1) / perPage + 1);
        drawStatus(g, left, this.height - 52, boxWidth);
    }
}
