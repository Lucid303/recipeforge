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
 * Minecraft has no official way to change these while playing, so it is done by reflection.
 * NeoForge keeps a biome's colours inside "ModifiableBiomeInfo" (so biome modifiers can change them),
 * and that is the object the game really reads, so that is what is swapped here.
 * Everything is guarded: if it fails the game keeps the normal colours and the reason is shown in
 * the Biome Forge screen and written to the log.
 */
public final class BiomeClient {
    private BiomeClient() {}

    public static volatile int version = 0;
    public static volatile String lastError = null;

    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();

    /** What we replaced, so it can be put back: [0] = old modified info (may be null), [1] = original effects, [2] = old effects field. */
    private static final Map<Biome, Object[]> ORIGINALS = new WeakHashMap<>();
    private static Field effectsField;

    private static Field effectsField() {
        if (effectsField == null) {
            for (Field f : Biome.class.getDeclaredFields()) {
                if (f.getType() == BiomeSpecialEffects.class) {
                    f.setAccessible(true);
                    effectsField = f;
                }
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
            String problem = null;
            for (Map.Entry<ResourceKey<Biome>, Biome> e : registry.entrySet()) {
                Biome biome = e.getValue();
                Map<String, Integer> colors = store.get(e.getKey().location().toString());
                if (colors == null || colors.isEmpty()) {
                    restore(biome);
                    continue;
                }
                modify(biome, colors);
                // check that the game now really reports the colour we asked for
                for (Map.Entry<String, Integer> c : colors.entrySet()) {
                    int now = BiomeServer.effective(biome, c.getKey());
                    if (now != (c.getValue() & 0xFFFFFF) && problem == null) {
                        problem = "the game still reports the old " + c.getKey() + " colour for " + e.getKey().location();
                    }
                }
            }
            lastError = problem;
            mc.level.clearTintCaches();
            mc.levelRenderer.allChanged();
            version++;
        } catch (Throwable ex) {
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            WorldForge.LOGGER.error("World Forge could not apply biome colours", ex);
            version++;
        }
    }

    // ------------------------------------------------------------ modifying a biome

    private static void modify(Biome biome, Map<String, Integer> colors) throws Exception {
        Object[] saved = ORIGINALS.get(biome);
        Field effects = effectsField();
        if (saved == null) {
            saved = new Object[3];
            saved[2] = effects == null ? null : effects.get(biome);
            ORIGINALS.put(biome, saved);
        }
        boolean done = false;

        // NeoForge path: the colours the game reads live in ModifiableBiomeInfo
        try {
            Method accessor = Biome.class.getMethod("modifiableBiomeInfo");
            Object mbi = accessor.invoke(biome);
            Method get = mbi.getClass().getMethod("get");
            Object info = get.invoke(mbi);
            Field modified = null;
            for (Field f : mbi.getClass().getDeclaredFields()) {
                if (f.getType() == info.getClass() && !Modifier.isFinal(f.getModifiers()) && !Modifier.isStatic(f.getModifiers())) {
                    modified = f;
                }
            }
            if (modified != null) {
                modified.setAccessible(true);
                if (saved[1] == null) {
                    saved[0] = modified.get(mbi);
                    saved[1] = findEffects(info);
                }
                BiomeSpecialEffects base = (BiomeSpecialEffects) saved[1];
                modified.set(mbi, rebuildInfo(info, withColors(base, colors)));
                done = true;
            }
        } catch (NoSuchMethodException ignored) {
            // not NeoForge's Biome: use the field below
        }

        // plain field (older / vanilla layout). Harmless to change as well.
        if (effects != null) {
            BiomeSpecialEffects base = saved[1] != null ? (BiomeSpecialEffects) saved[1] : (BiomeSpecialEffects) saved[2];
            if (base != null) {
                effects.set(biome, withColors(base, colors));
                done = true;
            }
        }
        if (!done) {
            throw new IllegalStateException("could not find where this game version keeps biome colours");
        }
    }

    private static void restore(Biome biome) {
        Object[] saved = ORIGINALS.get(biome);
        if (saved == null) {
            return;
        }
        try {
            Method accessor = Biome.class.getMethod("modifiableBiomeInfo");
            Object mbi = accessor.invoke(biome);
            Method get = mbi.getClass().getMethod("get");
            Object info = get.invoke(mbi);
            for (Field f : mbi.getClass().getDeclaredFields()) {
                if (f.getType() == info.getClass() && !Modifier.isFinal(f.getModifiers()) && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    f.set(mbi, saved[0]);
                }
            }
        } catch (Exception ignored) {
            // nothing to put back
        }
        try {
            if (effectsField() != null && saved[2] != null) {
                effectsField().set(biome, saved[2]);
            }
        } catch (Exception ignored) {
            // nothing to put back
        }
    }

    private static BiomeSpecialEffects findEffects(Object info) throws Exception {
        for (RecordComponent c : info.getClass().getRecordComponents()) {
            if (c.getType() == BiomeSpecialEffects.class) {
                Method m = c.getAccessor();
                m.setAccessible(true);
                return (BiomeSpecialEffects) m.invoke(info);
            }
        }
        throw new IllegalStateException("no colour settings inside the biome info");
    }

    private static Object rebuildInfo(Object info, BiomeSpecialEffects newEffects) throws Exception {
        RecordComponent[] comps = info.getClass().getRecordComponents();
        Object[] args = new Object[comps.length];
        Class<?>[] types = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            Method m = comps[i].getAccessor();
            m.setAccessible(true);
            args[i] = comps[i].getType() == BiomeSpecialEffects.class ? newEffects : m.invoke(info);
        }
        Constructor<?> ctor = info.getClass().getDeclaredConstructor(types);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    // ------------------------------------------------------------ building new colour settings

    private static BiomeSpecialEffects withColors(BiomeSpecialEffects original, Map<String, Integer> colors) throws Exception {
        if (BiomeSpecialEffects.class.isRecord()) {
            return viaRecord(original, colors);
        }
        return viaCopy(original, colors);
    }

    private static BiomeSpecialEffects viaRecord(BiomeSpecialEffects original, Map<String, Integer> colors) throws Exception {
        RecordComponent[] comps = BiomeSpecialEffects.class.getRecordComponents();
        Object[] args = new Object[comps.length];
        Class<?>[] types = new Class<?>[comps.length];
        int ints = 0;
        int optionals = 0;
        String[] intOrder = {"fog", "water", "water_fog", "sky"};
        String[] optOrder = {"foliage", "grass"};
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            Method accessor = comps[i].getAccessor();
            accessor.setAccessible(true);
            Object value = accessor.invoke(original);
            Object changed = override(comps[i].getName(), value, colors);
            if (changed == value) {
                // the name was not recognised (it may be scrambled in this setup): go by position instead
                if (types[i] == int.class) {
                    if (ints < 4 && colors.containsKey(intOrder[ints])) {
                        changed = colors.get(intOrder[ints]);
                    }
                } else if (types[i] == Optional.class && value instanceof Optional<?> opt
                        && (opt.isEmpty() || opt.get() instanceof Integer) && optionals < 2) {
                    if (colors.containsKey(optOrder[optionals])) {
                        changed = Optional.of(colors.get(optOrder[optionals]));
                    }
                }
            }
            if (types[i] == int.class) {
                ints++;
            } else if (types[i] == Optional.class && optionals < 2
                    && value instanceof Optional<?> opt2 && (opt2.isEmpty() || opt2.get() instanceof Integer)) {
                optionals++;
            }
            args[i] = changed;
        }
        Constructor<BiomeSpecialEffects> ctor = BiomeSpecialEffects.class.getDeclaredConstructor(types);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    private static BiomeSpecialEffects viaCopy(BiomeSpecialEffects original, Map<String, Integer> colors) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        BiomeSpecialEffects copy = (BiomeSpecialEffects) allocate.invoke(unsafe, BiomeSpecialEffects.class);

        List<Field> fields = new ArrayList<>();
        for (Field f : BiomeSpecialEffects.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                fields.add(f);
            }
        }
        String[] intOrder = {"fog", "water", "water_fog", "sky"};
        String[] optOrder = {"foliage", "grass"};
        int ints = 0;
        int optionals = 0;
        for (Field f : fields) {
            Object value = f.get(original);
            Object changed = override(f.getName(), value, colors);
            if (changed == value) {
                if (f.getType() == int.class && ints < 4 && colors.containsKey(intOrder[ints])) {
                    changed = colors.get(intOrder[ints]);
                } else if (f.getType() == Optional.class && value instanceof Optional<?> opt
                        && (opt.isEmpty() || opt.get() instanceof Integer) && optionals < 2
                        && colors.containsKey(optOrder[optionals])) {
                    changed = Optional.of(colors.get(optOrder[optionals]));
                }
            }
            if (f.getType() == int.class) {
                ints++;
            } else if (f.getType() == Optional.class && value instanceof Optional<?> opt2
                    && (opt2.isEmpty() || opt2.get() instanceof Integer) && optionals < 2) {
                optionals++;
            }
            f.set(copy, changed);
        }
        return copy;
    }

    /** Returns the same object when the name is not one of the colour fields. */
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
