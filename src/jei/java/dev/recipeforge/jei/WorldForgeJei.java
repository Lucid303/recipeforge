package dev.recipeforge.jei;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

import dev.recipeforge.StationClient;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeTypes;
import mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe;
import mezz.jei.api.recipe.vanilla.IVanillaRecipeFactory;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Shows World Forge's custom anvil combinations in JEI's anvil category. */
@JeiPlugin
public class WorldForgeJei implements IModPlugin {

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath("worldforge", "main");
    }

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
    public void registerRecipes(IRecipeRegistration registration) {
        IVanillaRecipeFactory factory = registration.getVanillaRecipeFactory();
        List<IJeiAnvilRecipe> recipes = new ArrayList<>();
        for (JsonObject e : StationClient.anvil) {
            try {
                if (e.has("mode") && e.get("mode").getAsString().equals("BLOCK")) {
                    continue;
                }
                ItemStack left = stack(e, "left", 1);
                ItemStack right = stack(e, "right", 1);
                ItemStack result = stack(e, "result", e.has("count") ? e.get("count").getAsInt() : 1);
                if (left.isEmpty() || right.isEmpty() || result.isEmpty()) {
                    continue;
                }
                recipes.add(factory.createAnvilRecipe(left, List.of(right), List.of(result)));
            } catch (Exception ignored) {
                // skip a broken entry
            }
        }
        if (!recipes.isEmpty()) {
            registration.addRecipes(RecipeTypes.ANVIL, recipes);
        }
    }
}
