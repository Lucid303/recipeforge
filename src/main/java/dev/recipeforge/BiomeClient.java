package dev.recipeforge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;

/**
 * Applies the saved biome colours to the client's copy of every biome (vanilla and modded).
 * Minecraft has no official way to change these while playing, so the biome's colour settings are
 * swapped by reflection. Everything is guarded: if it fails, the game just keeps the normal colours.
 */
public final class BiomeClient {
    private BiomeClient() {}

    public static volatile int version = 0;

    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();
    private static final Map<Biome, BiomeSpecialEffects> ORIGINALS = new WeakHashMap<>();
    private static Field effectsField;
    private static boolean failed = false;

    private static Field effectsField() throws Exception {
        if (effectsField == null) {
            for (Field f : Biome.class.getDeclaredFields()) {
                if (f.getType() == BiomeSpecialEffects.class) {
                    f.setAccessible(true);
                    effectsField = f;
                }
            }
            if (effectsField == null) {
                throw new IllegalStateException("could not find the biome colour settings");
            }
        }
        return effectsField;
    }

    public static void apply(String json) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || failed) {
            return;
        }
        try {
            Map<String, Map<String, Integer>> store = GSON.fromJson(json, TYPE);
            if (store == null) {
                store = new HashMap<>();
            }
            Registry<Biome> registry = mc.level.registryAccess().registryOrThrow(Registries.BIOME);
            for (Map.Entry<ResourceKey<Biome>, Biome> e : registry.entrySet()) {
                Biome biome = e.getValue();
                Map<String, Integer> colors = store.get(e.getKey().location().toString());
                BiomeSpecialEffects original = ORIGINALS.get(biome);
                if (colors == null || colors.isEmpty()) {
                    if (original != null) {
                        effectsField().set(biome, original);
                    }
                    continue;
                }
                if (original == null) {
                    original = (BiomeSpecialEffects) effectsField().get(biome);
                    ORIGINALS.put(biome, original);
                }
                effectsField().set(biome, withColors(original, colors));
            }
            mc.level.clearTintCaches();
            mc.levelRenderer.allChanged();
            version++;
        } catch (Exception ex) {
            failed = true;
            WorldForge.LOGGER.error("World Forge biome colours are not supported in this setup", ex);
        }
    }

    private static BiomeSpecialEffects withColors(BiomeSpecialEffects original, Map<String, Integer> colors) throws Exception {
        RecordComponent[] comps = BiomeSpecialEffects.class.getRecordComponents();
        if (comps == null) {
            throw new IllegalStateException("BiomeSpecialEffects is not a record in this version");
        }
        Object[] args = new Object[comps.length];
        Class<?>[] types = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            Method accessor = comps[i].getAccessor();
            accessor.setAccessible(true);
            Object value = accessor.invoke(original);
            switch (comps[i].getName()) {
                case "fogColor" -> value = colors.getOrDefault("fog", (Integer) value);
                case "waterColor" -> value = colors.getOrDefault("water", (Integer) value);
                case "waterFogColor" -> value = colors.getOrDefault("water_fog", (Integer) value);
                case "skyColor" -> value = colors.getOrDefault("sky", (Integer) value);
                case "foliageColorOverride" -> {
                    if (colors.containsKey("foliage")) {
                        value = Optional.of(colors.get("foliage"));
                    }
                }
                case "grassColorOverride" -> {
                    if (colors.containsKey("grass")) {
                        value = Optional.of(colors.get("grass"));
                    }
                }
                case "grassColorModifier" -> {
                    if (colors.containsKey("grass") && value instanceof Enum<?> en) {
                        value = en.getDeclaringClass().getEnumConstants()[0];   // NONE: use our colour exactly
                    }
                }
                default -> { }
            }
            args[i] = value;
        }
        Constructor<BiomeSpecialEffects> ctor = BiomeSpecialEffects.class.getDeclaredConstructor(types);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }
}
