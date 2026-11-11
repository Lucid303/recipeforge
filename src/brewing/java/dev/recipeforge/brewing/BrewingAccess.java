package dev.recipeforge.brewing;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import dev.recipeforge.WorldForge;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Reads and changes the potion mixes inside a PotionBrewing object.
 * Minecraft gives no official way to do this, so it is done by reflection and every step is guarded.
 */
public final class BrewingAccess {
    private BrewingAccess() {}

    private static Field potionMixesField;
    private static Method fromMethod;
    private static Method ingredientMethod;
    private static Method toMethod;
    private static Constructor<?> mixConstructor;
    private static boolean ready = false;
    private static boolean failed = false;

    private static synchronized boolean init() {
        if (ready) {
            return true;
        }
        if (failed) {
            return false;
        }
        try {
            for (Field f : PotionBrewing.class.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                if (f.getGenericType().getTypeName().contains("Mix<net.minecraft.world.item.alchemy.Potion>")) {
                    potionMixesField = f;
                }
            }
            if (potionMixesField == null) {
                throw new IllegalStateException("could not find the potion mix list");
            }
            potionMixesField.setAccessible(true);
            Class<?> mixClass = Class.forName("net.minecraft.world.item.alchemy.PotionBrewing$Mix");
            fromMethod = mixClass.getDeclaredMethod("from");
            ingredientMethod = mixClass.getDeclaredMethod("ingredient");
            toMethod = mixClass.getDeclaredMethod("to");
            fromMethod.setAccessible(true);
            ingredientMethod.setAccessible(true);
            toMethod.setAccessible(true);
            mixConstructor = mixClass.getDeclaredConstructors()[0];
            mixConstructor.setAccessible(true);
            ready = true;
            return true;
        } catch (Exception e) {
            failed = true;
            WorldForge.LOGGER.error("Recipe Forge brewing support is not available in this Minecraft setup", e);
            return false;
        }
    }

    private static String potionId(Object holderObject) {
        Holder<?> holder = (Holder<?>) holderObject;
        return holder.unwrapKey().map(k -> k.location().toString()).orElse("?");
    }

    private static String ingredientKey(Ingredient ingredient) {
        return Arrays.stream(ingredient.getItems())
                .map(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString())
                .distinct().sorted().collect(Collectors.joining(","));
    }

    private static BrewingData.MixRef refOf(Object mix) throws Exception {
        BrewingData.MixRef ref = new BrewingData.MixRef();
        ref.from = potionId(fromMethod.invoke(mix));
        ref.ingredient = ingredientKey((Ingredient) ingredientMethod.invoke(mix));
        ref.to = potionId(toMethod.invoke(mix));
        return ref;
    }

    /** Every potion mix that currently exists. */
    public static List<BrewingData.MixRef> read(PotionBrewing brewing) {
        List<BrewingData.MixRef> out = new ArrayList<>();
        if (brewing == null || !init()) {
            return out;
        }
        try {
            for (Object mix : (List<?>) potionMixesField.get(brewing)) {
                out.add(refOf(mix));
            }
        } catch (Exception e) {
            WorldForge.LOGGER.error("Recipe Forge could not read brewing mixes", e);
        }
        return out;
    }

    public static boolean remove(PotionBrewing brewing, BrewingData.MixRef ref) {
        if (brewing == null || !init()) {
            return false;
        }
        try {
            List<Object> copy = new ArrayList<>((List<?>) potionMixesField.get(brewing));
            boolean removed = false;
            for (int i = copy.size() - 1; i >= 0; i--) {
                if (refOf(copy.get(i)).key().equals(ref.key())) {
                    copy.remove(i);
                    removed = true;
                }
            }
            if (removed) {
                potionMixesField.set(brewing, copy);
            }
            return removed;
        } catch (Exception e) {
            WorldForge.LOGGER.error("Recipe Forge could not remove a brewing mix", e);
            return false;
        }
    }

    /** Adds a mix (single ingredient item). Returns false if it already exists or cannot be made. */
    public static boolean add(PotionBrewing brewing, BrewingData.MixRef ref) {
        if (brewing == null || !init()) {
            return false;
        }
        try {
            ResourceLocation fromId = ResourceLocation.tryParse(ref.from);
            ResourceLocation toId = ResourceLocation.tryParse(ref.to);
            ResourceLocation itemId = ResourceLocation.tryParse(ref.ingredient);
            if (fromId == null || toId == null || itemId == null) {
                return false;
            }
            Holder<Potion> from = BuiltInRegistries.POTION.getHolder(fromId).orElse(null);
            Holder<Potion> to = BuiltInRegistries.POTION.getHolder(toId).orElse(null);
            Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
            if (from == null || to == null || item == null) {
                return false;
            }
            List<Object> copy = new ArrayList<>((List<?>) potionMixesField.get(brewing));
            for (Object mix : copy) {
                if (refOf(mix).key().equals(ref.key())) {
                    return false;
                }
            }
            copy.add(mixConstructor.newInstance(from, Ingredient.of(item), to));
            potionMixesField.set(brewing, copy);
            return true;
        } catch (Exception e) {
            WorldForge.LOGGER.error("Recipe Forge could not add a brewing mix", e);
            return false;
        }
    }

    /** Moves a PotionBrewing from the "prev" settings to the "next" settings. */
    public static void apply(PotionBrewing brewing, BrewingData.Store prev, BrewingData.Store next) {
        for (BrewingData.MixRef r : prev.added) {
            if (!BrewingData.containsRef(next.added, r)) {
                remove(brewing, r);
            }
        }
        for (BrewingData.MixRef r : prev.removed) {
            if (!BrewingData.containsRef(next.removed, r)) {
                add(brewing, r);
            }
        }
        for (BrewingData.MixRef r : next.removed) {
            remove(brewing, r);
        }
        for (BrewingData.MixRef r : next.added) {
            add(brewing, r);
        }
    }
}
