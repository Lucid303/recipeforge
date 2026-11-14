package dev.recipeforge.emi;

import java.util.List;

import com.google.gson.JsonObject;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories;
import dev.emi.emi.api.render.EmiTexture;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import dev.recipeforge.StationClient;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shows World Forge's custom anvil and grindstone combinations in EMI. */
@EmiEntrypoint
public class WorldForgeEmi implements EmiPlugin {
    private static final EmiRecipeCategory GRINDSTONE = new EmiRecipeCategory(
            ResourceLocation.fromNamespaceAndPath("worldforge", "grindstone"), EmiStack.of(Items.GRINDSTONE));

    private static ItemStack stack(JsonObject o, String field, int count) {
        if (!o.has(field) || o.get(field).isJsonNull()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation rl = ResourceLocation.tryParse(o.get(field).getAsString());
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        return BuiltInRegistries.ITEM.getOptional(rl).map(i -> new ItemStack(i, count)).orElse(ItemStack.EMPTY);
    }

    @Override
    public void register(EmiRegistry registry) {
        registry.addCategory(GRINDSTONE);
        registry.addWorkstation(GRINDSTONE, EmiStack.of(Items.GRINDSTONE));
        int n = 0;
        for (JsonObject e : StationClient.anvil) {
            n = add(registry, VanillaEmiRecipeCategories.ANVIL_REPAIRING, "anvil", n, e);
        }
        for (JsonObject e : StationClient.grindstone) {
            n = add(registry, GRINDSTONE, "grindstone", n, e);
        }
    }

    private int add(EmiRegistry registry, EmiRecipeCategory category, String name, int n, JsonObject e) {
        try {
            if (e.has("mode") && e.get("mode").getAsString().equals("BLOCK")) {
                return n;
            }
            ItemStack left = stack(e, "left", 1);
            ItemStack right = stack(e, "right", 1);
            ItemStack result = stack(e, "result", e.has("count") ? e.get("count").getAsInt() : 1);
            if (left.isEmpty() || right.isEmpty() || result.isEmpty()) {
                return n;
            }
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("worldforge", "/" + name + "/" + n);
            registry.addRecipe(new PairRecipe(category, id, EmiStack.of(left), EmiStack.of(right), EmiStack.of(result)));
            return n + 1;
        } catch (Exception ex) {
            return n;
        }
    }

    private static final class PairRecipe implements EmiRecipe {
        private final EmiRecipeCategory category;
        private final ResourceLocation id;
        private final EmiStack left;
        private final EmiStack right;
        private final EmiStack result;

        PairRecipe(EmiRecipeCategory category, ResourceLocation id, EmiStack left, EmiStack right, EmiStack result) {
            this.category = category;
            this.id = id;
            this.left = left;
            this.right = right;
            this.result = result;
        }

        @Override
        public EmiRecipeCategory getCategory() {
            return category;
        }

        @Override
        public ResourceLocation getId() {
            return id;
        }

        @Override
        public List<EmiIngredient> getInputs() {
            return List.of(left, right);
        }

        @Override
        public List<EmiStack> getOutputs() {
            return List.of(result);
        }

        @Override
        public int getDisplayWidth() {
            return 125;
        }

        @Override
        public int getDisplayHeight() {
            return 18;
        }

        @Override
        public void addWidgets(WidgetHolder widgets) {
            widgets.addSlot(left, 0, 0);
            widgets.addTexture(EmiTexture.PLUS, 27, 3);
            widgets.addSlot(right, 49, 0);
            widgets.addTexture(EmiTexture.EMPTY_ARROW, 75, 1);
            widgets.addSlot(result, 107, 0).recipeContext(this);
        }
    }
}
