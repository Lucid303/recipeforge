package dev.recipeforge.brewing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import dev.recipeforge.WorldForge;
import net.neoforged.fml.loading.FMLPaths;

/** Brewing changes kept in config/recipeforge/brewing.json (server side). */
public final class BrewingData {
    private BrewingData() {}

    public static class MixRef {
        public String from;        // potion id, e.g. minecraft:awkward
        public String ingredient;  // item id (vanilla mixes may list several, comma separated)
        public String to;          // potion id

        public String key() {
            return from + "|" + ingredient + "|" + to;
        }
    }

    public static class Store {
        public List<MixRef> added = new ArrayList<>();
        public List<MixRef> removed = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Store store = null;

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("worldforge").resolve("brewing.json");
    }

    public static synchronized Store get() {
        if (store == null) {
            store = new Store();
            Path f = file();
            if (Files.exists(f)) {
                try {
                    Store loaded = GSON.fromJson(Files.readString(f), Store.class);
                    if (loaded != null) {
                        store = loaded;
                        if (store.added == null) {
                            store.added = new ArrayList<>();
                        }
                        if (store.removed == null) {
                            store.removed = new ArrayList<>();
                        }
                    }
                } catch (Exception e) {
                    WorldForge.LOGGER.error("Recipe Forge could not read brewing.json", e);
                }
            }
        }
        return store;
    }

    public static synchronized void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(get()));
        } catch (IOException e) {
            WorldForge.LOGGER.error("Recipe Forge could not write brewing.json", e);
        }
    }

    public static synchronized String toJson() {
        return GSON.toJson(get());
    }

    private static boolean removeFrom(List<MixRef> list, MixRef ref) {
        return list.removeIf(r -> r.key().equals(ref.key()));
    }

    public static boolean containsRef(List<MixRef> list, MixRef ref) {
        for (MixRef r : list) {
            if (r.key().equals(ref.key())) {
                return true;
            }
        }
        return false;
    }

    public static synchronized void addedAdd(MixRef ref) {
        if (!containsRef(get().added, ref)) {
            get().added.add(ref);
        }
    }

    public static synchronized boolean addedRemove(MixRef ref) {
        return removeFrom(get().added, ref);
    }

    public static synchronized void removedAdd(MixRef ref) {
        if (!containsRef(get().removed, ref)) {
            get().removed.add(ref);
        }
    }

    public static synchronized boolean removedRemove(MixRef ref) {
        return removeFrom(get().removed, ref);
    }
}
