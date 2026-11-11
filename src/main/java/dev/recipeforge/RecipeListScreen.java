package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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

/** Shows every recipe that makes one item, with Edit / Remove / Undo buttons. */
public class RecipeListScreen extends ForgeScreen {
    private static final int ROW = 56;

    private final ResourceLocation key;
    private final ItemStack stack;
    private List<RecipeHolder<?>> recipes = new ArrayList<>();
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int width0;
    private int top = 44;
    private int seenVersion = -1;
    private String awaitingId = null;
    private ItemStack hoverStack = ItemStack.EMPTY;

    public RecipeListScreen(Screen parent, ResourceLocation key, ItemStack stack) {
        super(Component.literal("Recipes"), parent);
        this.key = key;
        this.stack = stack;
    }

    private void loadRecipes() {
        recipes = new ArrayList<>();
        if (this.minecraft != null && this.minecraft.level != null) {
            RecipeData.Info info = RecipeData.build(this.minecraft.level).get(key);
            if (info != null) {
                recipes = info.recipes();
            }
        }
        seenVersion = ClientState.recipeVersion;
    }

    @Override
    protected void init() {
        loadRecipes();
        width0 = Math.min(this.width - 20, 460);
        left = (this.width - width0) / 2;
        int bottom = this.height - 36;
        perPage = Math.max(1, (bottom - top) / ROW);
        page = Math.max(0, Math.min(page, (recipes.size() - 1) / perPage));

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= recipes.size()) {
                break;
            }
            final RecipeHolder<?> holder = recipes.get(index);
            int y = top + i * ROW;
            int bx = left + width0 - 128;
            this.addRenderableWidget(Button.builder(Component.literal("Edit"), b -> edit(holder))
                    .bounds(bx, y + 2, 60, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Remove"), b -> remove(holder))
                    .bounds(bx + 64, y + 2, 60, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Undo my changes"), b -> restore(holder))
                    .bounds(bx, y + 26, 124, 20).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Add new recipe"), b -> addNew())
                .bounds(left, this.height - 28, 110, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(left + width0 - 70, this.height - 28, 70, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(this.width / 2 - 50, this.height - 28, 30, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(this.width / 2 + 20, this.height - 28, 30, 20).build());
    }

    private void changePage(int delta) {
        int max = Math.max(0, (recipes.size() - 1) / perPage);
        int newPage = Math.max(0, Math.min(max, page + delta));
        if (newPage != page) {
            page = newPage;
            this.rebuildWidgets();
        }
    }

    private void edit(RecipeHolder<?> holder) {
        awaitingId = holder.id().toString();
        PacketDistributor.sendToServer(new RecipeEditPayload("GET_RECIPE", awaitingId, ""));
        status = "Opening...";
    }

    private void remove(RecipeHolder<?> holder) {
        PacketDistributor.sendToServer(new RecipeEditPayload("DISABLE", holder.id().toString(), ""));
        status = "Removing... (you can undo this later with Undo my changes or in History)";
    }

    private void restore(RecipeHolder<?> holder) {
        PacketDistributor.sendToServer(new RecipeEditPayload("RESTORE", holder.id().toString(), ""));
        status = "Putting the original back...";
    }

    private void addNew() {
        this.minecraft.setScreen(new RecipeTypeChooserScreen(this, key.toString()));
    }

    @Override
    protected void onForgeTick() {
        if (seenVersion != ClientState.recipeVersion) {
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        hoverStack = ItemStack.EMPTY;

        g.renderItem(stack, left, 12);
        g.drawString(this.font, "Recipes that make " + stack.getHoverName().getString(), left + 22, 17, 0xFFFFFF);

        if (recipes.isEmpty()) {
            g.drawCenteredString(this.font, "No recipes for this item right now.", this.width / 2, 80, 0xFF5555);
            g.drawCenteredString(this.font, "Press \"Add new recipe\" to make one.", this.width / 2, 94, 0xAAAAAA);
        }

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= recipes.size()) {
                break;
            }
            RecipeHolder<?> holder = recipes.get(index);
            int y = top + i * ROW;
            g.fill(left, y - 2, left + width0, y + ROW - 6, 0x40FFFFFF);
            drawRecipe(g, holder, left + 6, y, mouseX, mouseY);
        }

        g.drawCenteredString(this.font, "Page " + (page + 1) + " / "
                + (Math.max(0, (recipes.size() - 1) / perPage) + 1), this.width / 2, this.height - 42, 0xAAAAAA);
        drawStatus(g, left, this.height - 54, width0);

        if (!hoverStack.isEmpty()) {
            g.renderTooltip(this.font, hoverStack.getHoverName(), mouseX, mouseY);
        }
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
        int textWidth = width0 - 128 - 104;
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
