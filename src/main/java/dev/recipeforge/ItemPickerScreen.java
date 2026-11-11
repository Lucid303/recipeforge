package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A full-screen "pick one item" window. Used by the loot and structure editors. */
public class ItemPickerScreen extends ForgeScreen {
    private static final int CELL = 20;

    private final String heading;
    private final boolean blocksOnly;
    private final Consumer<String> onPick;
    private EditBox searchBox;
    private final List<ItemStack> filtered = new ArrayList<>();
    private String lastSearch = "";
    private int page = 0;
    private int cols = 1;
    private int rows = 1;
    private int gx;
    private final int gy = 52;

    public ItemPickerScreen(Screen parent, String heading, boolean blocksOnly, Consumer<String> onPick) {
        super(Component.literal(heading), parent);
        this.heading = heading;
        this.blocksOnly = blocksOnly;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        String prev = searchBox != null ? searchBox.getValue() : lastSearch;
        cols = Math.max(1, Math.min(22, (this.width - 20) / CELL));
        rows = Math.max(1, (this.height - gy - 36) / CELL);
        gx = (this.width - cols * CELL) / 2;

        searchBox = new EditBox(this.font, this.width / 2 - 100, 26, 200, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search  (try @minecraft)"));
        searchBox.setValue(prev);
        searchBox.setResponder(t -> {
            page = 0;
            applyFilter(t);
        });
        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width - 78, this.height - 28, 70, 20).build());
        if (blocksOnly) {
            this.addRenderableWidget(Button.builder(Component.literal("Air (remove)"), b -> choose("minecraft:air"))
                    .bounds(10, this.height - 28, 90, 20).build());
        }
        addPager(Math.max(260, this.width / 2), this.height - 28, () -> changePage(-1), () -> changePage(1));
        applyFilter(prev);
    }

    private void applyFilter(String text) {
        lastSearch = text == null ? "" : text;
        SearchFilter f = SearchFilter.parse(lastSearch, GridEditorScreen.namespaces());
        filtered.clear();
        for (ItemStack s : GridEditorScreen.allItems()) {
            if (blocksOnly && !(s.getItem() instanceof BlockItem)) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (f.matches(key, s.getHoverName().getString())) {
                filtered.add(s);
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

    private void choose(String id) {
        onPick.accept(id);
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, new StationInfo.Info(heading, Items.COMPASS, 0xFF555555),
                "Click an item");
        int start = page * perPage();
        ItemStack hover = ItemStack.EMPTY;
        for (int i = 0; i < perPage() && start + i < filtered.size(); i++) {
            ItemStack s = filtered.get(start + i);
            int x = gx + (i % cols) * CELL;
            int y = gy + (i / cols) * CELL;
            Ui.slot(g, x, y, CELL - 2);
            g.renderItem(s, x + 1, y + 1);
            if (Ui.inside(mouseX, mouseY, x, y, CELL - 2, CELL - 2)) {
                hover = s;
            }
        }
        drawPager(g, Math.max(260, this.width / 2), this.height - 28, page, maxPage() + 1);
        if (!hover.isEmpty()) {
            g.renderTooltip(this.font, hover.getHoverName(), mouseX, mouseY);
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
                ItemStack s = filtered.get(start + i);
                if (blocksOnly && s.getItem() instanceof BlockItem blockItem) {
                    choose(BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString());
                } else {
                    choose(GridEditorScreen.keyOf(s));
                }
                return true;
            }
        }
        return false;
    }

    @Override
    protected void scrollPage(int delta) {
        changePage(delta);
    }
}
