package dev.recipeforge.extras;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.recipeforge.ForgeScreen;
import dev.recipeforge.SearchFilter;
import dev.recipeforge.StationInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

/** A simple "pick one name from a list" screen (tags, entity types ...). */
public class IdPickerScreen extends ForgeScreen {
    private static final int ROW = 18;

    private final String heading;
    private final List<String> ids;
    private final Consumer<String> onPick;
    private final List<String> filtered = new ArrayList<>();
    private EditBox searchBox;
    private String lastFilter = "";
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private final int top = 54;

    public IdPickerScreen(Screen parent, String heading, List<String> ids, Consumer<String> onPick) {
        super(Component.literal(heading), parent);
        this.heading = heading;
        this.ids = ids;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        String prev = searchBox != null ? searchBox.getValue() : lastFilter;
        boxWidth = Math.min(this.width - 20, 460);
        left = (this.width - boxWidth) / 2;
        perPage = Math.max(1, (this.height - top - 40) / ROW);
        forgetDynamic();
        searchBox = new EditBox(this.font, left, 28, 220, 20, Component.literal("Search"));
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
        for (String id : ids) {
            String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
            if (!namespaces.contains(ns)) {
                namespaces.add(ns);
            }
        }
        SearchFilter f = SearchFilter.parse(lastFilter, namespaces);
        filtered.clear();
        for (String id : ids) {
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
            addDynamic(Button.builder(Component.literal(this.font.plainSubstrByWidth(id, boxWidth - 10)), b -> {
                onPick.accept(id);
                this.onClose();
            }).bounds(left, top + i * ROW, boxWidth, ROW - 2).build());
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
        StationInfo.banner(g, this.font, this.width, new StationInfo.Info(heading, Items.COMPASS, 0xFF555555), "Click one");
        drawPager(g, this.width / 2, this.height - 28, page, (filtered.size() - 1) / perPage + 1);
    }
}
