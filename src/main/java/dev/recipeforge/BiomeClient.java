package dev.recipeforge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * swapped by reflection. Everything is guarded: if it fails, the game keeps the normal colours and
 * the reason is shown in the Biome Forge screen and written to the log.
 */
public final class BiomeClient {
    private BiomeClient() {}

    public static volatile int version = 0;
    public static volatile String lastError = null;

    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();
    private static final Map<Biome, BiomeSpecialEffects> ORIGINALS = new WeakHashMap<>();
    private static Field effectsField;

    private static Field effectsField() {
        if (effectsField == null) {
            for (Field f : Biome.class.getDeclaredFields()) {
                if (f.getType() == BiomeSpecialEffects.class) {
                    f.setAccessible(true);
                    effectsField = f;
                }
            }
            if (effectsField == null) {
                throw new IllegalStateException("could not find the biome colour settings inside Biome");
            }
        }
        return effectsField;
    }

    public static void apply(String json) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
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
            lastError = null;
            mc.level.clearTintCaches();
            mc.levelRenderer.allChanged();
            version++;
        } catch (Throwable ex) {
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            WorldForge.LOGGER.error("World Forge could not apply biome colours", ex);
            version++;
        }
    }

    private static BiomeSpecialEffects withColors(BiomeSpecialEffects original, Map<String, Integer> colors) throws Exception {
        if (BiomeSpecialEffects.class.isRecord()) {
            return viaRecord(original, colors);
        }
        return viaCopy(original, colors);
    }

    /** Newer versions: BiomeSpecialEffects is a record, so build a new one with its constructor. */
    private static BiomeSpecialEffects viaRecord(BiomeSpecialEffects original, Map<String, Integer> colors) throws Exception {
        RecordComponent[] comps = BiomeSpecialEffects.class.getRecordComponents();
        Object[] args = new Object[comps.length];
        Class<?>[] types = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            Method accessor = comps[i].getAccessor();
            accessor.setAccessible(true);
            args[i] = override(comps[i].getName(), accessor.invoke(original), colors);
        }
        Constructor<BiomeSpecialEffects> ctor = BiomeSpecialEffects.class.getDeclaredConstructor(types);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    /** Older versions: a normal class. Make a copy of it without calling a constructor, then change its fields. */
    private static BiomeSpecialEffects viaCopy(BiomeSpecialEffects original, Map<String, Integer> colors) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        BiomeSpecialEffects copy = (BiomeSpecialEffects) allocate.invoke(unsafe, BiomeSpecialEffects.class);

        List<Field> fields = new ArrayList<>();
        for (Field f : BiomeSpecialEffects.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            fields.add(f);
        }
        List<Field> intFields = new ArrayList<>();
        List<Field> optionalFields = new ArrayList<>();
        for (Field f : fields) {
            f.set(copy, f.get(original));
            if (f.getType() == int.class) {
                intFields.add(f);
            } else if (f.getType() == Optional.class) {
                optionalFields.add(f);
            }
        }
        // fields are matched by name; if the names are different in this setup, fall back to their order
        for (Field f : fields) {
            Object value = override(f.getName(), f.get(original), colors);
            f.set(copy, value);
        }
        String[] intOrder = {"fog", "water", "water_fog", "sky"};
        for (int i = 0; i < intFields.size() && i < 4; i++) {
            Field f = intFields.get(i);
            boolean known = f.getName().equals("fogColor") || f.getName().equals("waterColor")
                    || f.getName().equals("waterFogColor") || f.getName().equals("skyColor");
            if (!known && colors.containsKey(intOrder[i])) {
                f.set(copy, colors.get(intOrder[i]));
            }
        }
        String[] optOrder = {"foliage", "grass"};
        for (int i = 0; i < optionalFields.size() && i < 2; i++) {
            Field f = optionalFields.get(i);
            boolean known = f.getName().equals("foliageColorOverride") || f.getName().equals("grassColorOverride");
            if (!known && colors.containsKey(optOrder[i])) {
                f.set(copy, Optional.of(colors.get(optOrder[i])));
            }
        }
        return copy;
    }

    private static Object override(String name, Object value, Map<String, Integer> colors) {
        switch (name) {
            case "fogColor":
                return colors.containsKey("fog") ? colors.get("fog") : value;
            case "waterColor":
                return colors.containsKey("water") ? colors.get("water") : value;
            case "waterFogColor":
                return colors.containsKey("water_fog") ? colors.get("water_fog") : value;
            case "skyColor":
                return colors.containsKey("sky") ? colors.get("sky") : value;
            case "foliageColorOverride":
                return colors.containsKey("foliage") ? Optional.of(colors.get("foliage")) : value;
            case "grassColorOverride":
                return colors.containsKey("grass") ? Optional.of(colors.get("grass")) : value;
            case "grassColorModifier":
                if (colors.containsKey("grass") && value instanceof Enum<?> en) {
                    return en.getDeclaringClass().getEnumConstants()[0];   // NONE: use our colour exactly
                }
                return value;
            default:
                return value;
        }
    }
}
