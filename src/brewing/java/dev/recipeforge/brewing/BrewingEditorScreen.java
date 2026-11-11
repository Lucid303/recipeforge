package dev.recipeforge.brewing;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import dev.recipeforge.ForgeScreen;
import dev.recipeforge.GridEditorScreen;
import dev.recipeforge.RecipeEditPayload;
import dev.recipeforge.SearchFilter;
import dev.recipeforge.StationInfo;
import dev.recipeforge.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.neoforge.network.PacketDistributor;

/** Three squares: start potion, ingredient item, result potion. */
public class BrewingEditorScreen extends ForgeScreen {
    private static final int STEP = 22;
    private static final int BOX = 20;
    private static final int CELL = 18;
    private static final Gson GSON = new Gson();
    private static final String[] LABELS = {"Start potion", "Ingredient", "Result potion"};

    private static List<ItemStack> potionStacks = null;
    private static List<ResourceLocation> potionIds = null;

    private final BrewingData.MixRef old;      // null when adding a new brew
    private String from;
    private String ingredient;
    private String to;
    private int selected = 0;

    private EditBox searchBox;
    private final List<ItemStack> filtered = new ArrayList<>();
    private final List<String> filteredIds = new ArrayList<>();
    private int page = 0;
    private String lastSearch = "";
    private int gridX;
    private int gridY;
    private int pickX;
    private int pickY;
    private int pickCols;
    private int pickRows;

    public BrewingEditorScreen(Screen parent, BrewingData.MixRef old) {
        super(Component.literal("Brewing"), parent);
        this.old = old;
        if (old != null) {
            this.from = old.from;
            this.ingredient = old.ingredient;
            this.to = old.to;
        }
    }

    private static void loadPotions() {
        if (potionStacks != null) {
            return;
        }
        List<ItemStack> stacks = new ArrayList<>();
        List<ResourceLocation> ids = new ArrayList<>();
        BuiltInRegistries.POTION.holders().forEach(holder -> {
            Holder<Potion> h = holder;
            stacks.add(PotionContents.createItemStack(Items.POTION, h));
            ids.add(holder.key().location());
        });
        potionStacks = stacks;
        potionIds = ids;
    }

    @Override
    protected void init() {
        loadPotions();
        String prev = searchBox != null ? searchBox.getValue() : lastSearch;

        pickCols = Math.max(5, Math.min(12, (this.width - 20 - 150) / CELL));
        int total = 138 + pickCols * CELL;
        gridX = Math.max(8, (this.width - total) / 2);
        gridY = 66;
        pickX = gridX + 138;
        pickY = 50;
        pickRows = Math.max(2, (this.height - pickY - 36) / CELL);

        searchBox = new EditBox(this.font, pickX, 24, pickCols * CELL, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search (@minecraft)"));
        searchBox.setValue(prev);
        searchBox.setResponder(t -> {
            page = 0;
            applyFilter(t);
        });
        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);

        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(gridX, this.height - 28, 64, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
                .bounds(gridX + 68, this.height - 28, 64, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(pickX, this.height - 28, 20, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(pickX + pickCols * CELL - 20, this.height - 28, 20, 20).build());
        applyFilter(prev);
    }

    private boolean potionMode() {
        return selected != 1;
    }

    private void applyFilter(String text) {
        lastSearch = text == null ? "" : text;
        filtered.clear();
        filteredIds.clear();
        if (potionMode()) {
            List<String> namespaces = new ArrayList<>();
            for (ResourceLocation id : potionIds) {
                namespaces.add(id.getNamespace());
            }
            SearchFilter f = SearchFilter.parse(lastSearch, namespaces);
            for (int i = 0; i < potionStacks.size(); i++) {
                if (f.matches(potionIds.get(i), potionStacks.get(i).getHoverName().getString())) {
                    filtered.add(potionStacks.get(i));
                    filteredIds.add(potionIds.get(i).toString());
                }
            }
        } else {
            SearchFilter f = SearchFilter.parse(lastSearch, GridEditorScreen.namespaces());
            for (ItemStack s : GridEditorScreen.allItems()) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
                if (f.matches(key, s.getHoverName().getString())) {
                    filtered.add(s);
                    filteredIds.add(key.toString());
                }
            }
        }
        page = Math.min(page, maxPage());
    }

    private int perPage() {
        return Math.max(1, pickCols * pickRows);
    }

    private int maxPage() {
        return Math.max(0, (filtered.size() - 1) / perPage());
    }

    private void changePage(int delta) {
        page = Math.max(0, Math.min(maxPage(), page + delta));
    }

    private void select(int index) {
        boolean wasPotion = potionMode();
        selected = index;
        if (wasPotion != potionMode()) {
            page = 0;
            applyFilter(searchBox.getValue());
        }
    }

    private void set(String id) {
        switch (selected) {
            case 0 -> from = id;
            case 1 -> ingredient = id;
            default -> to = id;
        }
        if (selected < 2) {
            select(selected + 1);
        }
    }

    private void save() {
        if (from == null || ingredient == null || to == null) {
            status = "Fill all three squares first.";
            return;
        }
        BrewingData.MixRef mix = new BrewingData.MixRef();
        mix.from = from;
        mix.ingredient = ingredient;
        mix.to = to;
        if (old == null) {
            PacketDistributor.sendToServer(new RecipeEditPayload("BREW_ADD", "", GSON.toJson(mix)));
        } else {
            JsonObject o = new JsonObject();
            o.add("old", GSON.toJsonTree(old));
            o.add("new", GSON.toJsonTree(mix));
            PacketDistributor.sendToServer(new RecipeEditPayload("BREW_REPLACE", "", o.toString()));
        }
        status = "Saving...";
    }

    @Override
    public void onServerResult(boolean ok, String message) {
        status = message;
        if (ok && this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    private ItemStack stackFor(int i) {
        return switch (i) {
            case 0 -> BrewingScreen.potionStack(from);
            case 1 -> BrewingScreen.ingredientStack(ingredient);
            default -> BrewingScreen.potionStack(to);
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        for (int i = 0; i < 3; i++) {
            if (Ui.inside(mouseX, mouseY, gridX, gridY + i * STEP, BOX, BOX)) {
                select(i);
                if (button == 1) {
                    switch (i) {
                        case 0 -> from = null;
                        case 1 -> ingredient = null;
                        default -> to = null;
                    }
                }
                return true;
            }
        }
        if (button == 0) {
            int start = page * perPage();
            for (int i = 0; i < perPage() && start + i < filtered.size(); i++) {
                int x = pickX + (i % pickCols) * CELL;
                int y = pickY + (i / pickCols) * CELL;
                if (Ui.inside(mouseX, mouseY, x, y, CELL - 2, CELL - 2)) {
                    set(filteredIds.get(start + i));
                    return true;
                }
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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, StationInfo.brewing(), old != null ? "Editing a brew" : "New brew");
        g.drawString(this.font, "1) Click a square", gridX, 24, 0xFFFFFF);
        g.drawString(this.font, "2) Click an item ->", gridX, 35, 0xFFFFFF);
        g.drawString(this.font, "Right-click = empty it", gridX, 46, 0xAAAAAA);

        Component hover = null;
        for (int i = 0; i < 3; i++) {
            int x = gridX;
            int y = gridY + i * STEP;
            Ui.slot(g, x, y, BOX);
            ItemStack s = stackFor(i);
            if (!s.isEmpty()) {
                g.renderItem(s, x + 2, y + 2);
            }
            if (selected == i) {
                Ui.outline(g, x - 1, y - 1, BOX + 2, BOX + 2, 0xFFFFFF55);
            }
            g.drawString(this.font, LABELS[i], x + BOX + 4, y + 6, 0xAAAAAA);
            if (Ui.inside(mouseX, mouseY, x, y, BOX, BOX) && !s.isEmpty()) {
                hover = s.getHoverName();
            }
        }
        g.drawString(this.font, "Only potions are changed here.", gridX, gridY + 3 * STEP + 6, 0x888888);
        g.drawString(this.font, "Splash/lingering follow along.", gridX, gridY + 3 * STEP + 17, 0x888888);

        int start = page * perPage();
        for (int i = 0; i < perPage() && start + i < filtered.size(); i++) {
            ItemStack s = filtered.get(start + i);
            int x = pickX + (i % pickCols) * CELL;
            int y = pickY + (i / pickCols) * CELL;
            Ui.slot(g, x, y, CELL - 2);
            g.renderItem(s, x + 1, y + 1);
            if (Ui.inside(mouseX, mouseY, x, y, CELL - 2, CELL - 2)) {
                hover = s.getHoverName();
            }
        }
        g.drawCenteredString(this.font, "Page " + (page + 1) + " / " + (maxPage() + 1),
                pickX + pickCols * CELL / 2, this.height - 22, 0xAAAAAA);
        drawStatus(g, gridX, this.height - 42, this.width - 2 * gridX);
        if (hover != null) {
            g.renderTooltip(this.font, hover, mouseX, mouseY);
        }
    }
}
