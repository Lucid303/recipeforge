package dev.recipeforge.extras;

import java.util.ArrayList;
import java.util.List;

import dev.recipeforge.ForgeScreen;
import dev.recipeforge.SearchFilter;
import dev.recipeforge.StationInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

/** Tag Forge: pick a tag (like minecraft:planks) to add items to or take items out of. */
public class TagForgeScreen extends ForgeScreen {
    private static final int ROW = 18;
    static final StationInfo.Info INFO = new StationInfo.Info("Tag Forge", Items.NAME_TAG, 0xFF2E7D6B);
    private static final String[] REGISTRIES = {"item", "block", "entity_type"};
    private static final String[] LABELS = {"Items", "Blocks", "Mobs"};

    private String registry = "item";
    private final List<String> allTags = new ArrayList<>();
    private final List<String> filtered = new ArrayList<>();
    private EditBox searchBox;
    private String lastFilter = "";
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private final int top = 76;

    public TagForgeScreen(Screen parent) {
        super(Component.literal("Tag Forge"), parent);
    }

    static <T> List<String> tagNames(Registry<T> reg) {
        List<String> out = new ArrayList<>();
        reg.getTagNames().forEach(t -> out.add(t.location().toString()));
        out.sort(String::compareTo);
        return out;
    }

    private void loadTags() {
        allTags.clear();
        switch (registry) {
            case "block" -> allTags.addAll(tagNames(BuiltInRegistries.BLOCK));
            case "entity_type" -> allTags.addAll(tagNames(BuiltInRegistries.ENTITY_TYPE));
            default -> allTags.addAll(tagNames(BuiltInRegistries.ITEM));
        }
    }

    @Override
    protected void init() {
        loadTags();
        String prev = searchBox != null ? searchBox.getValue() : lastFilter;
        boxWidth = Math.min(this.width - 20, 460);
        left = (this.width - boxWidth) / 2;
        perPage = Math.max(1, (this.height - top - 40) / ROW);
        forgetDynamic();

        for (int i = 0; i < REGISTRIES.length; i++) {
            final String key = REGISTRIES[i];
            this.addRenderableWidget(Button.builder(Component.literal(key.equals(registry) ? "[" + LABELS[i] + "]" : LABELS[i]), b -> {
                registry = key;
                page = 0;
                this.rebuildWidgets();
            }).bounds(left + i * 84, 26, 80, 20).build());
        }
        searchBox = new EditBox(this.font, left, 50, 220, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search tags  (try @minecraft)"));
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
        for (String id : allTags) {
            String ns = id.substring(0, id.indexOf(':'));
            if (!namespaces.contains(ns)) {
                namespaces.add(ns);
            }
        }
        SearchFilter f = SearchFilter.parse(lastFilter, namespaces);
        filtered.clear();
        for (String id : allTags) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && f.matches(rl, rl.getPath().replace('_', ' ').replace('/', ' '))) {
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
            addDynamic(Button.builder(Component.literal(this.font.plainSubstrByWidth("#" + id, boxWidth - 10)),
                    b -> this.minecraft.setScreen(new TagScreen(this, registry, id)))
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, INFO, "Add or remove items from tags");
        drawPager(g, this.width / 2, this.height - 28, page, (filtered.size() - 1) / perPage + 1);
    }
}
