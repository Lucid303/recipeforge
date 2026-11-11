package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.neoforged.neoforge.network.PacketDistributor;

/** Shows every recipe (and custom anvil / grindstone combination) for one item, with Edit / Remove buttons. */
public class RecipeListScreen extends ForgeScreen {
    private static final int ROW = 52;

    private record Row(RecipeHolder<?> holder, RecipeData.StationRef station) {}

    private final ResourceLocation key;
    private final ItemStack stack;
    private final List<Row> rows = new ArrayList<>();
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private final int top = 46;
    private int seenRecipeVersion = -1;
    private int seenStationVersion = -1;
    private String awaitingId = null;
    private ItemStack hoverStack = ItemStack.EMPTY;

    public RecipeListScreen(Screen parent, ResourceLocation key, ItemStack stack) {
        super(Component.literal("Recipes"), parent);
        this.key = key;
        this.stack = stack;
    }

    private void loadRows() {
        rows.clear();
        seenRecipeVersion = ClientState.recipeVersion;
        seenStationVersion = StationClient.version;
        if (this.minecraft != null && this.minecraft.level != null) {
            RecipeData.Info info = RecipeData.build(this.minecraft.level).get(key);
            if (info != null) {
                for (RecipeHolder<?> h : info.recipes()) {
                    rows.add(new Row(h, null));
                }
                for (RecipeData.StationRef s : info.stations()) {
                    rows.add(new Row(null, s));
                }
            }
        }
    }

    @Override
    protected void init() {
        loadRows();
        boxWidth = Math.min(this.width - 20, 460);
        left = (this.width - boxWidth) / 2;
        perPage = Math.max(1, (this.height - top - 66) / ROW);
        page = Math.max(0, Math.min(page, (rows.size() - 1) / perPage));

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= rows.size()) {
                break;
            }
            final Row row = rows.get(index);
            int y = top + i * ROW;
            int bx = left + boxWidth - 128;
            if (row.holder() != null) {
                final RecipeHolder<?> holder = row.holder();
                this.addRenderableWidget(Button.builder(Component.literal("Edit"), b -> edit(holder))
                        .bounds(bx, y + 2, 60, 20).tooltip(Tooltip.create(Component.literal("Change this recipe."))).build());
                this.addRenderableWidget(Button.builder(Component.literal("Remove"), b -> remove(holder))
                        .bounds(bx + 64, y + 2, 60, 20)
                        .tooltip(Tooltip.create(Component.literal("Turn this recipe off. You can undo it."))).build());
                this.addRenderableWidget(Button.builder(Component.literal("Undo my changes"), b -> restore(holder))
                        .bounds(bx, y + 26, 124, 20)
                        .tooltip(Tooltip.create(Component.literal("Put the original recipe back."))).build());
            } else {
                final RecipeData.StationRef ref = row.station();
                final String entryId = ref.entry().get("id").getAsString();
                this.addRenderableWidget(Button.builder(Component.literal("Edit"), b ->
                        this.minecraft.setScreen(new GridEditorScreen(this, ref.entry(), entryId, null,
                                "recipeforge:" + ref.station())))
                        .bounds(bx, y + 2, 60, 20).build());
                this.addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
                    PacketDistributor.sendToServer(new RecipeEditPayload("STATION_REMOVE", ref.station(), entryId));
                    status = "Removing...";
                }).bounds(bx + 64, y + 2, 60, 20).build());
            }
        }

        this.addRenderableWidget(Button.builder(Component.literal("Add new recipe"), b ->
                this.minecraft.setScreen(new RecipeTypeChooserScreen(this, key.toString())))
                .bounds(left, this.height - 28, 110, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width - 78, this.height - 28, 70, 20).build());
        addPager(this.width / 2, this.height - 28, () -> changePage(-1), () -> changePage(1));
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
    protected void scrollPage(int delta) {
        changePage(delta);
    }

    private void edit(RecipeHolder<?> holder) {
        awaitingId = holder.id().toString();
        PacketDistributor.sendToServer(new RecipeEditPayload("GET_RECIPE", awaitingId, ""));
        status = "Opening...";
    }

    private void remove(RecipeHolder<?> holder) {
        PacketDistributor.sendToServer(new RecipeEditPayload("DISABLE", holder.id().toString(), ""));
        status = "Removing... (you can undo this with Undo my changes or in History)";
    }

    private void restore(RecipeHolder<?> holder) {
        PacketDistributor.sendToServer(new RecipeEditPayload("RESTORE", holder.id().toString(), ""));
        status = "Putting the original back...";
    }

    @Override
    protected void onForgeTick() {
        if (seenRecipeVersion != ClientState.recipeVersion || seenStationVersion != StationClient.version) {
            this.rebuildWidgets();
        }
    }

    @Override
    public void onRecipeJson(String id, String json) {
        if (!id.equals(awaitingId)) {
            return;
        }
        awaitingId = null;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : "";
            if (GridEditorScreen.supports(type)) {
                this.minecraft.setScreen(new GridEditorScreen(this, obj, id, null, null));
            } else {
                this.minecraft.setScreen(new EditorScreen(this, id, json));
            }
        } catch (Exception e) {
            status = "Could not open this recipe: " + e.getMessage();
        }
    }

    @Override
    public void onRecipeError(String id, String message) {
        awaitingId = null;
        status = message;
    }

    @Override
    protected void drawUnderWidgets(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = 0; i < perPage; i++) {
            if (page * perPage + i >= rows.size()) {
                break;
            }
            int y = top + i * ROW;
            g.fill(left, y - 2, left + boxWidth, y + ROW - 6, 0x40FFFFFF);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        hoverStack = ItemStack.EMPTY;
        StationInfo.banner(g, this.font, this.width, StationInfo.recipes(), "Recipes for one item");

        g.renderItem(stack, left, 25);
        g.drawString(this.font, "Recipes for " + stack.getHoverName().getString(), left + 22, 30, 0xFFFFFF);

        if (rows.isEmpty()) {
            g.drawCenteredString(this.font, "No recipes for this item right now.", this.width / 2, 80, 0xFF5555);
            g.drawCenteredString(this.font, "Press \"Add new recipe\" to make one.", this.width / 2, 94, 0xAAAAAA);
        }

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= rows.size()) {
                break;
            }
            Row row = rows.get(index);
            int y = top + i * ROW;
            if (row.holder() != null) {
                drawRecipe(g, row.holder(), left + 6, y, mouseX, mouseY);
            } else {
                drawStationEntry(g, row.station(), left + 6, y, mouseX, mouseY);
            }
        }

        drawPager(g, this.width / 2, this.height - 28, page, (rows.size() - 1) / perPage + 1);
        drawStatus(g, left, this.height - 62, boxWidth);
        if (!hoverStack.isEmpty()) {
            g.renderTooltip(this.font, hoverStack.getHoverName(), mouseX, mouseY);
        }
    }

    private void icon(GuiGraphics g, ItemStack s, int x, int y, int mouseX, int mouseY) {
        if (s.isEmpty()) {
            return;
        }
        g.renderItem(s, x, y);
        g.renderItemDecorations(this.font, s, x, y);
        if (Ui.inside(mouseX, mouseY, x, y, 16, 16)) {
            hoverStack = s;
        }
    }

    private static ItemStack stackOf(JsonObject o, String field) {
        if (!o.has(field) || o.get(field).isJsonNull()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation rl = ResourceLocation.tryParse(o.get(field).getAsString());
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        return BuiltInRegistries.ITEM.getOptional(rl).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    private void drawStationEntry(GuiGraphics g, RecipeData.StationRef ref, int x, int y, int mouseX, int mouseY) {
        JsonObject e = ref.entry();
        boolean block = e.has("mode") && e.get("mode").getAsString().equals("BLOCK");
        icon(g, stackOf(e, "left"), x, y + 14, mouseX, mouseY);
        g.drawString(this.font, "+", x + 20, y + 18, 0xFFFFFF);
        icon(g, stackOf(e, "right"), x + 30, y + 14, mouseX, mouseY);
        int textX = x + 96;
        if (block) {
            g.drawString(this.font, "BLOCKED", x + 54, y + 18, 0xFF5555);
        } else {
            g.drawString(this.font, "->", x + 54, y + 18, 0xFFFFFF);
            ItemStack r = stackOf(e, "result");
            if (!r.isEmpty()) {
                r = r.copyWithCount(e.has("count") ? e.get("count").getAsInt() : 1);
            }
            icon(g, r, x + 70, y + 14, mouseX, mouseY);
            String extra = ref.station().equals("anvil")
                    ? "Costs " + (e.has("cost") ? e.get("cost").getAsInt() : 1) + " levels"
                    : "Gives " + (e.has("xp") ? e.get("xp").getAsInt() : 0) + " XP";
            g.drawString(this.font, extra, textX, y + 6, 0xAAAAAA);
        }
        StationInfo.Info info = StationInfo.forType("recipeforge:" + ref.station());
        g.renderItem(new ItemStack(info.icon()), textX, y + 28);
        g.drawString(this.font, info.name(), textX + 20, y + 33, 0xFFFFFF55);
    }

    private void drawRecipe(GuiGraphics g, RecipeHolder<?> holder, int x, int y, int mouseX, int mouseY) {
        Recipe<?> recipe = holder.value();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        int columns = 3;
        if (recipe instanceof ShapedRecipe shaped) {
            columns = Math.max(1, shaped.getWidth());
        }
        int count = Math.min(ingredients.size(), 9);
        for (int i = 0; i < count; i++) {
            int cx = x + (i % columns) * 16;
            int cy = y + (i / columns) * 16;
            ItemStack shown = Ui.cycle(ingredients.get(i).getItems());
            if (!shown.isEmpty()) {
                g.renderItem(shown, cx, cy);
                if (Ui.inside(mouseX, mouseY, cx, cy, 16, 16)) {
                    hoverStack = shown;
                }
            }
        }
        g.drawString(this.font, "->", x + 52, y + 18, 0xFFFFFF);

        ItemStack result = ItemStack.EMPTY;
        if (this.minecraft != null && this.minecraft.level != null) {
            try {
                result = recipe.getResultItem(this.minecraft.level.registryAccess());
            } catch (Exception ignored) {
                // leave empty
            }
        }
        if (!result.isEmpty()) {
            g.renderItem(result, x + 68, y + 16);
            g.renderItemDecorations(this.font, result, x + 68, y + 16);
            if (Ui.inside(mouseX, mouseY, x + 68, y + 16, 16, 16)) {
                hoverStack = result;
            }
        }

        int textX = x + 96;
        int textWidth = boxWidth - 128 - 104;
        ResourceLocation typeKey = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        g.drawString(this.font, this.font.plainSubstrByWidth(holder.id().toString(), textWidth),
                textX, y + 6, 0xFFFFFF);
        g.drawString(this.font, this.font.plainSubstrByWidth(typeKey == null ? "?" : typeKey.toString(), textWidth),
                textX, y + 18, 0xAAAAAA);
        StationInfo.Info station = StationInfo.forType(typeKey == null ? null : typeKey.toString());
        g.renderItem(new ItemStack(station.icon()), textX, y + 28);
        g.drawString(this.font, this.font.plainSubstrByWidth(station.name(), textWidth - 20),
                textX + 20, y + 33, 0xFFFFFF55);
    }
}
