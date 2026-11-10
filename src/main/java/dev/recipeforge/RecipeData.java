package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Groups every recipe the client knows about by the item it makes. */
public final class RecipeData {
    private RecipeData() {}

    public record Info(ResourceLocation key, ItemStack stack, List<RecipeHolder<?>> recipes) {}

    public static Map<ResourceLocation, Info> build(ClientLevel level) {
        Map<ResourceLocation, Info> map = new TreeMap<>();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            ItemStack result;
            try {
                result = holder.value().getResultItem(level.registryAccess());
            } catch (Exception e) {
                continue;
            }
            if (result == null || result.isEmpty()) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(result.getItem());
            map.computeIfAbsent(key, k -> new Info(k, new ItemStack(result.getItem()), new ArrayList<>()))
                    .recipes().add(holder);
        }
        return map;
    }
}
