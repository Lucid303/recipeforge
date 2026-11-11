package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/** Tick the biomes where one structure is allowed to spawn. */
public class StructureBiomesScreen extends ForgeScreen {
    private static final int ROW = 16;

    private final String structureId;
    private final Set<String> selected = new TreeSet<>();
    private final List<String> allBiomes = new ArrayList<>();
    private final List<String> filtered = new ArrayList<>();
    private EditBox searchBox;
    private String lastFilter = "";
    private boolean loaded = false;
    private boolean requested = false;
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private final int top = 54;

    public StructureBiomesScreen(Screen parent, String structureId) {
        super(Component.literal("Structure biomes"), parent);
        this.structureId = structureId;
        this.status = "Loading...";
    }

    @Override
    protected void init() {
        if (!requested) {
            requested = true;
            PacketDistributor.sendToServer(new RecipeEditPayload("STRUCT_GET", structureId, ""));
        }
        allBiomes.clear();
        if (this.minecraft != null && this.minecraft.level != null) {
            for (ResourceLocation rl : this.minecraft.level.registryAccess().registryOrThrow(Registries.BIOME).keySet()) {
                allBiomes.add(rl.toString());
            }
            allBiomes.sort(String::compareTo);
        }
        String prev = searchBox != null ? searchBox.getValue() : lastFilter;
        boxWidth = Math.min(this.width - 20, 460);
        left = (this.width - boxWidth) / 2;
        perPage = Math.max(1, (this.height - top - 84) / ROW);

        searchBox = new EditBox(this.font, left, 28, 220, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search biomes  (try @minecraft)"));
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
        if (loaded) {
            for (int i = 0; i < perPage; i++) {
                int index = page * perPage + i;
                if (index >= filtered.size()) {
                    break;
                }
                final String id = filtered.get(index);
                this.addRenderableWidget(Button.builder(
                        Component.literal((selected.contains(id) ? "[x] " : "[  ] ") + id), b -> {
                            if (!selected.remove(id)) {
                                selected.add(id);
                            }
                            this.rebuildWidgets();
                        }).bounds(left, top + i * ROW, boxWidth, ROW - 1).build());
            }
        }

        int by = this.height - 28;
        String[] labels = {"All shown", "None shown", "Never spawn", "Save", "Restore"};
        String[] tips = {"Tick every biome in the list below.", "Untick every biome in the list below.",
                "Untick everything, then Save: this structure will never spawn.", "Apply to the game.",
                "Put the original biomes back."};
        for (int i = 0; i < 5; i++) {
            final int which = i;
            this.addRenderableWidget(Button.builder(Component.literal(labels[i]), b -> bottomButton(which))
                    .bounds(left + i * 84, by, 80, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(tips[i]))).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width - 78, by, 70, 20).build());
        addPager(this.width / 2, this.height - 52, () -> changePage(-1), () -> changePage(1));
    }

    private void applyFilter(String text) {
        lastFilter = text == null ? "" : text;
        List<String> namespaces = new ArrayList<>();
        for (String id : allBiomes) {
            String ns = id.substring(0, id.indexOf(':'));
            if (!namespaces.contains(ns)) {
                namespaces.add(ns);
            }
        }
        SearchFilter f = SearchFilter.parse(lastFilter, namespaces);
        filtered.clear();
        for (String id : allBiomes) {
            if (f.matches(ResourceLocation.parse(id), id.substring(id.indexOf(':') + 1).replace('_', ' '))) {
                filtered.add(id);
            }
        }
    }

    @Override
    protected void scrollPage(int delta) {
        changePage(delta);
    }

    private void changePage(int delta) {
        int max = Math.max(0, (filtered.size() - 1) / perPage);
        int newPage = Math.max(0, Math.min(max, page + delta));
        if (newPage != page) {
            page = newPage;
            this.rebuildWidgets();
        }
    }

    private void bottomButton(int which) {
        switch (which) {
            case 0 -> {
                selected.addAll(filtered);
                this.rebuildWidgets();
            }
            case 1 -> {
                selected.removeAll(filtered);
                this.rebuildWidgets();
            }
            case 2 -> {
                selected.clear();
                this.rebuildWidgets();
                status = "Nothing is ticked: press Save and this structure will never spawn.";
            }
            case 3 -> {
                JsonArray array = new JsonArray();
                selected.forEach(array::add);
                PacketDistributor.sendToServer(new RecipeEditPayload("STRUCT_SET_BIOMES", structureId, array.toString()));
                status = "Saving...";
            }
            case 4 -> {
                PacketDistributor.sendToServer(new RecipeEditPayload("STRUCT_RESTORE", structureId, ""));
                status = "Putting the original back...";
            }
            default -> { }
        }
    }

    @Override
    public void onData(String kind, String id, String data) {
        if (!id.equals(structureId)) {
            return;
        }
        if (kind.equals("STRUCT")) {
            selected.clear();
            try {
                for (JsonElement e : JsonParser.parseString(data).getAsJsonArray()) {
                    selected.add(e.getAsString());
                }
                loaded = true;
                status = "Tick the biomes, then press Save. You must re-enter the world afterwards.";
            } catch (Exception ex) {
                status = "Could not read this structure: " + ex.getMessage();
            }
            this.rebuildWidgets();
        } else if (kind.equals("STRUCT_ERROR")) {
            status = data;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, StationInfo.structure(), structureId);
        drawPager(g, this.width / 2, this.height - 52, page, (filtered.size() - 1) / perPage + 1);
        g.drawString(this.font, selected.size() + " ticked", left, this.height - 46, 0xAAAAAA);
        drawStatus(g, left, this.height - 82, boxWidth);
    }
}
