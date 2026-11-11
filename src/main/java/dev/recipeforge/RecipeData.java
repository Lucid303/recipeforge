package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonObject;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Groups every recipe (and custom anvil / grindstone combination) by the item it involves. */
public final class RecipeData {
    private RecipeData() {}

    public record StationRef(String station, JsonObject entry) {}

    public record Info(ResourceLocation key, ItemStack stack, List<RecipeHolder<?>> recipes, List<StationRef> stations) {
        public int total() {
            return recipes.size() + stations.size();
        }
    }

    private static Info infoFor(Map<ResourceLocation, Info> map, Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return map.computeIfAbsent(key, k -> new Info(k, new ItemStack(item), new ArrayList<>(), new ArrayList<>()));
    }

    private static Item itemOf(JsonObject o, String field) {
        if (!o.has(field) || o.get(field).isJsonNull()) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(o.get(field).getAsString());
        return rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

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
            infoFor(map, result.getItem()).recipes().add(holder);
        }
        for (String station : new String[] {"anvil", "grindstone"}) {
            for (JsonObject entry : StationClient.of(station)) {
                try {
                    boolean block = entry.has("mode") && entry.get("mode").getAsString().equals("BLOCK");
                    StationRef ref = new StationRef(station, entry);
                    if (block) {
                        Item left = itemOf(entry, "left");
                        Item right = itemOf(entry, "right");
                        if (left != null) {
                            infoFor(map, left).stations().add(ref);
                        }
                        if (right != null && right != left) {
                            infoFor(map, right).stations().add(ref);
                        }
                    } else {
                        Item result = itemOf(entry, "result");
                        if (result != null) {
                            infoFor(map, result).stations().add(ref);
                        }
                    }
                } catch (Exception ignored) {
                    // skip a broken entry
                }
            }
        }
        return map;
    }
}
