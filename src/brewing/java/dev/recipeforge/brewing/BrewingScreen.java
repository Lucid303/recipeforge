package dev.recipeforge.brewing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.google.gson.Gson;

import dev.recipeforge.ClientState;
import dev.recipeforge.ForgeScreen;
import dev.recipeforge.RecipeEditPayload;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.neoforge.network.PacketDistributor;

/** Lists every potion brew (vanilla and changed ones) with Edit / Remove / Put back buttons. */
public class BrewingScreen extends ForgeScreen {
    private static final int ROW = 30;
    private static final Gson GSON = new Gson();

    private record Row(BrewingData.MixRef ref, boolean removed) {}

    private final List<Row> rows = new ArrayList<>();
    private final List<Row> filtered = new ArrayList<>();
    private EditBox searchBox;
    private String lastFilter = "";
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private int top = 52;
    private int seenBrewVersion = -1;
    private int seenRecipeVersion = -1;
    private ItemStack hoverStack = ItemStack.EMPTY;

    public BrewingScreen(Screen parent) {
        super(Component.literal("Brewing stand"), parent);
    }

    // ---- helpers also used by the editor
    public static ItemStack potionStack(String id) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        Optional<Holder.Reference<Potion>> holder = BuiltInRegistries.POTION.getHolder(rl);
        if (holder.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return PotionContents.createItemStack(Items.POTION, holder.get());
    }

    public static ItemStack ingredientStack(String ids) {
        if (ids == null || ids.isEmpty()) {
            return ItemStack.EMPTY;
        }
        String first = ids.contains(",") ? ids.substring(0, ids.indexOf(',')) : ids;
        ResourceLocation rl = ResourceLocation.tryParse(first);
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    private void loadRows() {
        rows.clear();
        seenBrewVersion = BrewingClient.version;
        seenRecipeVersion = ClientState.recipeVersion;
        if (this.minecraft != null && this.minecraft.level != null) {
            for (BrewingData.MixRef ref : BrewingAccess.read(this.minecraft.level.potionBrewing())) {
                rows.add(new Row(ref, false));
            }
        }
        for (BrewingData.MixRef ref : BrewingClient.store.removed) {
            rows.add(new Row(ref, true));
        }
    }

    private static String describe(BrewingData.MixRef r) {
        return (potionStack(r.from).isEmpty() ? r.from : potionStack(r.from).getHoverName().getString())
                + " + " + (ingredientStack(r.ingredient).isEmpty() ? r.ingredient
                : ingredientStack(r.ingredient).getHoverName().getString());
    }

    @Override
    protected void init() {
        String prev = searchBox != null ? searchBox.getValue() : lastFilter;
        loadRows();
        boxWidth = Math.min(this.width - 20, 460);
        left = (this.width - boxWidth) / 2;
        perPage = Math.max(1, (this.height - top - 40) / ROW);

        searchBox = new EditBox(this.font, left, 26, 200, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search brews..."));
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
            final Row row = filtered.get(index);
            int y = top + i * ROW;
            int bx = left + boxWidth - 128;
            if (row.removed()) {
                this.addRenderableWidget(Button.builder(Component.literal("Put back"), b -> {
                    PacketDistributor.sendToServer(new RecipeEditPayload("BREW_RESTORE", "", GSON.toJson(row.ref())));
                    status = "Putting it back...";
                }).bounds(bx, y + 2, 124, 20).build());
            } else {
                if (!row.ref().ingredient.contains(",")) {
                    this.addRenderableWidget(Button.builder(Component.literal("Edit"), b ->
                            this.minecraft.setScreen(new BrewingEditorScreen(this, row.ref())))
                            .bounds(bx, y + 2, 60, 20).build());
                }
                this.addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
                    PacketDistributor.sendToServer(new RecipeEditPayload("BREW_REMOVE", "", GSON.toJson(row.ref())));
                    status = "Removing...";
                }).bounds(bx + 64, y + 2, 60, 20).build());
            }
        }

        this.addRenderableWidget(Button.builder(Component.literal("Add new brew"), b ->
                this.minecraft.setScreen(new BrewingEditorScreen(this, null)))
                .bounds(left, this.height - 28, 110, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(left + boxWidth - 70, this.height - 28, 70, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(left + 190, this.height - 28, 26, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(left + 280, this.height - 28, 26, 20).build());
    }

    private void applyFilter(String text) {
        lastFilter = text == null ? "" : text;
        String f = lastFilter.trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (Row r : rows) {
            String hay = (r.ref().key() + " " + describe(r.ref())).toLowerCase(Locale.ROOT);
            if (f.isEmpty() || hay.contains(f)) {
                filtered.add(r);
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
    protected void onForgeTick() {
        if (seenBrewVersion != BrewingClient.version || seenRecipeVersion != ClientState.recipeVersion) {
            this.rebuildWidgets();
        }
    }

    private void icon(GuiGraphics g, ItemStack stack, int x, int y, int mouseX, int mouseY) {
        if (stack.isEmpty()) {
            return;
        }
        g.renderItem(stack, x, y);
        if (Ui.inside(mouseX, mouseY, x, y, 16, 16)) {
            hoverStack = stack;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        hoverStack = ItemStack.EMPTY;
        StationInfo.banner(g, this.font, this.width, StationInfo.brewing(), "Potion brews");
        g.drawString(this.font, "Start potion + ingredient -> result potion", left + 210, 32, 0xAAAAAA);

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= filtered.size()) {
                break;
            }
            Row row = filtered.get(index);
            BrewingData.MixRef r = row.ref();
            int y = top + i * ROW;
            g.fill(left, y - 2, left + boxWidth, y + ROW - 4, row.removed() ? 0x40FF5555 : 0x40FFFFFF);
            icon(g, potionStack(r.from), left + 4, y + 2, mouseX, mouseY);
            g.drawString(this.font, "+", left + 24, y + 6, 0xFFFFFF);
            icon(g, ingredientStack(r.ingredient), left + 32, y + 2, mouseX, mouseY);
            g.drawString(this.font, "->", left + 52, y + 6, 0xFFFFFF);
            icon(g, potionStack(r.to), left + 66, y + 2, mouseX, mouseY);
            String text = describe(r) + " -> " + (potionStack(r.to).isEmpty() ? r.to
                    : potionStack(r.to).getHoverName().getString());
            g.drawString(this.font, this.font.plainSubstrByWidth((row.removed() ? "(removed) " : "") + text,
                    boxWidth - 128 - 92), left + 88, y + 6, row.removed() ? 0xFF8888 : 0xFFFFFF);
        }
        g.drawCenteredString(this.font, "Page " + (page + 1) + " / "
                + (Math.max(0, (filtered.size() - 1) / perPage) + 1), left + 243, this.height - 42, 0xAAAAAA);
        drawStatus(g, left, this.height - 54, boxWidth);
        if (!hoverStack.isEmpty()) {
            g.renderTooltip(this.font, hoverStack.getHoverName(), mouseX, mouseY);
        }
    }
}
