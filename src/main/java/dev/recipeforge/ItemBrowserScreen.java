package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Main screen: a grid of items. Click one to see every recipe that makes it. */
public class ItemBrowserScreen extends ForgeScreen {
    private static final int CELL = 20;

    private EditBox searchBox;
    private Map<ResourceLocation, RecipeData.Info> data = new TreeMap<>();
    private final List<RecipeData.Info> filtered = new ArrayList<>();
    private int page = 0;
    private int cols = 1;
    private int rows = 1;
    private int gx;
    private int gy = 56;
    private int seenVersion = -1;
    private String lastFilter = "";

    public ItemBrowserScreen(Screen parent) {
        super(Component.literal("Recipe Forge"), parent);
    }

    @Override
    protected void init() {
        String prev = searchBox != null ? searchBox.getValue() : lastFilter;
        if (this.minecraft != null && this.minecraft.level != null) {
            data = RecipeData.build(this.minecraft.level);
        }
        seenVersion = ClientState.recipeVersion;

        cols = Math.max(1, Math.min(22, (this.width - 20) / CELL));
        rows = Math.max(1, (this.height - gy - 36) / CELL);
        gx = (this.width - cols * CELL) / 2;

        searchBox = new EditBox(this.font, this.width / 2 - 100, 22, 200, 20, Component.literal("Search"));
        searchBox.setMaxLength(100);
        searchBox.setHint(Component.literal("Type to search items..."));
        searchBox.setValue(prev);
        searchBox.setResponder(text -> {
            page = 0;
            applyFilter(text);
        });
        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);

        this.addRenderableWidget(Button.builder(Component.literal("History"),
                b -> this.minecraft.setScreen(new HistoryScreen(this))).bounds(10, 22, 70, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Close"),
                b -> this.onClose()).bounds(this.width - 80, 22, 70, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("New recipe"),
                b -> this.minecraft.setScreen(new GridEditorScreen(this, null, null, null)))
                .bounds(10, this.height - 28, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("<"),
                b -> changePage(-1)).bounds(this.width / 2 - 60, this.height - 28, 40, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"),
                b -> changePage(1)).bounds(this.width / 2 + 20, this.height - 28, 40, 20).build());

        applyFilter(prev);
    }

    private void applyFilter(String text) {
        lastFilter = text == null ? "" : text;
        String f = lastFilter.trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (RecipeData.Info info : data.values()) {
            if (f.isEmpty()
                    || info.key().toString().contains(f)
                    || info.stack().getHoverName().getString().toLowerCase(Locale.ROOT).contains(f)) {
                filtered.add(info);
            }
        }
        page = Math.min(page, maxPage());
    }

    private int perPage() {
        return Math.max(1, cols * rows);
    }

    private int maxPage() {
        return Math.max(0, (filtered.size() - 1) / perPage());
    }

    private void changePage(int delta) {
        page = Math.max(0, Math.min(maxPage(), page + delta));
    }

    @Override
    protected void onForgeTick() {
        if (seenVersion != ClientState.recipeVersion) {
            this.rebuildWidgets();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        g.drawCenteredString(this.font, "Click an item to see its recipes", this.width / 2, 45, 0xAAAAAA);

        int start = page * perPage();
        RecipeData.Info hover = null;
        for (int i = 0; i < perPage() && start + i < filtered.size(); i++) {
            RecipeData.Info info = filtered.get(start + i);
            int x = gx + (i % cols) * CELL;
            int y = gy + (i / cols) * CELL;
            Ui.slot(g, x, y, CELL - 2);
            g.renderItem(info.stack(), x + 1, y + 1);
            if (Ui.inside(mouseX, mouseY, x, y, CELL - 2, CELL - 2)) {
                hover = info;
            }
        }
        if (filtered.isEmpty()) {
            g.drawCenteredString(this.font, "No items found", this.width / 2, this.height / 2, 0xFF5555);
        }
        g.drawCenteredString(this.font, "Page " + (page + 1) + " / " + (maxPage() + 1),
                this.width / 2, this.height - 22, 0xAAAAAA);
        if (hover != null) {
            int n = hover.recipes().size();
            g.renderTooltip(this.font, Component.literal(hover.stack().getHoverName().getString()
                    + "  (" + n + (n == 1 ? " recipe)" : " recipes)")), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        int start = page * perPage();
        for (int i = 0; i < perPage() && start + i < filtered.size(); i++) {
            int x = gx + (i % cols) * CELL;
            int y = gy + (i / cols) * CELL;
            if (Ui.inside(mouseX, mouseY, x, y, CELL - 2, CELL - 2)) {
                RecipeData.Info info = filtered.get(start + i);
                this.minecraft.setScreen(new RecipeListScreen(this, info.key(), info.stack()));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            changePage(scrollY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
