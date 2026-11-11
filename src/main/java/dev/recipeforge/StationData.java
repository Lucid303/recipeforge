package dev.recipeforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import net.neoforged.fml.loading.FMLPaths;

/** Custom anvil and grindstone combinations, kept in config/recipeforge/stations.json. */
public final class StationData {
    private StationData() {}

    public static class Pair {
        public String id;
        public String mode = "ADD";   // ADD = make an item, BLOCK = prevent the combination
        public String left;
        public String right;
        public String result;
        public int count = 1;
        public int cost = 1;          // anvil: XP levels
        public int xp = 0;            // grindstone: XP points
    }

    private static class Store {
        List<Pair> anvil = new ArrayList<>();
        List<Pair> grindstone = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Store store = null;

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("worldforge").resolve("stations.json");
    }

    private static void load() {
        if (store != null) {
            return;
        }
        store = new Store();
        Path f = file();
        if (Files.exists(f)) {
            try {
                Store loaded = GSON.fromJson(Files.readString(f), Store.class);
                if (loaded != null) {
                    store = loaded;
                    if (store.anvil == null) {
                        store.anvil = new ArrayList<>();
                    }
                    if (store.grindstone == null) {
                        store.grindstone = new ArrayList<>();
                    }
                }
            } catch (Exception e) {
                WorldForge.LOGGER.error("Recipe Forge could not read stations.json", e);
            }
        }
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(store));
        } catch (IOException e) {
            WorldForge.LOGGER.error("Recipe Forge could not write stations.json", e);
        }
    }

    private static List<Pair> listFor(String station) {
        return station.equals("anvil") ? store.anvil : store.grindstone;
    }

    public static synchronized String toSyncJson() {
        load();
        JsonObject o = new JsonObject();
        o.add("anvil", GSON.toJsonTree(store.anvil));
        o.add("grindstone", GSON.toJsonTree(store.grindstone));
        return o.toString();
    }

    public static synchronized List<Pair> snapshot(String station) {
        load();
        return new ArrayList<>(listFor(station));
    }

    public static synchronized void put(String station, Pair pair) {
        load();
        List<Pair> list = listFor(station);
        for (int i = 0; i < list.size(); i++) {
            if (pair.id.equals(list.get(i).id)) {
                list.set(i, pair);
                save();
                return;
            }
        }
        list.add(pair);
        save();
    }

    public static synchronized boolean remove(String station, String id) {
        load();
        boolean removed = listFor(station).removeIf(p -> id.equals(p.id));
        if (removed) {
            save();
        }
        return removed;
    }
}
