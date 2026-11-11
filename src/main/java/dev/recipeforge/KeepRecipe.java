package dev.recipeforge;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * A normal shaped or shapeless crafting recipe where some ingredients are NOT used up
 * (for example an axe that stays in the grid). It wraps a vanilla recipe and only changes
 * what is left in the grid after crafting.
 */
public class KeepRecipe implements CraftingRecipe {
    private final Recipe<CraftingInput> delegate;
    private final List<Ingredient> keep;
    private final boolean shaped;

    public KeepRecipe(Recipe<CraftingInput> delegate, List<Ingredient> keep, boolean shaped) {
        this.delegate = delegate;
        this.keep = keep;
        this.shaped = shaped;
    }

    public Recipe<CraftingInput> delegate() {
        return delegate;
    }

    public List<Ingredient> keep() {
        return keep;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return delegate.matches(input, level);
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return delegate.assemble(input, registries);
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return delegate.canCraftInDimensions(width, height);
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return delegate.getResultItem(registries);
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        return delegate.getIngredients();
    }

    @Override
    public String getGroup() {
        return delegate.getGroup();
    }

    @Override
    public boolean isIncomplete() {
        return delegate.isIncomplete();
    }

    @Override
    public CraftingBookCategory category() {
        return delegate instanceof CraftingRecipe c ? c.category() : CraftingBookCategory.MISC;
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> remaining = delegate.getRemainingItems(input);
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            for (Ingredient k : keep) {
                if (k.test(stack)) {
                    remaining.set(i, stack.copyWithCount(1));
                    break;
                }
            }
        }
        return remaining;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return shaped ? ModRecipes.KEEP_SHAPED.get() : ModRecipes.KEEP_SHAPELESS.get();
    }
}
