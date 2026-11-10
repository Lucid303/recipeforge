package dev.recipeforge;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.neoforged.fml.loading.FMLPaths;

/** A saved list of every edit, kept in config/recipeforge/history.json. */
public final class History {
    private History() {}

    public static class Entry {
        public long seq;
        public String time;
        public String player;
        public String action;
        public String id;
        public String json;
    }

    private static final int MAX_ENTRIES = 200;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static List<Entry> entries = null;
    private static long nextSeq = 1;

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("recipeforge").resolve("history.json");
    }

    private static void load() {
        if (entries != null) {
            return;
        }
        entries = new ArrayList<>();
        Path f = file();
        if (Files.exists(f)) {
            try {
                Type type = new TypeToken<List<Entry>>() {}.getType();
                List<Entry> loaded = GSON.fromJson(Files.readString(f), type);
                if (loaded != null) {
                    entries.addAll(loaded);
                }
            } catch (Exception e) {
                RecipeForge.LOGGER.error("Recipe Forge could not read history.json", e);
            }
        }
        for (Entry e : entries) {
            nextSeq = Math.max(nextSeq, e.seq + 1);
        }
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(entries));
        } catch (IOException e) {
            RecipeForge.LOGGER.error("Recipe Forge could not write history.json", e);
        }
    }

    public static synchronized void add(String player, String action, String id, String json) {
        load();
        Entry e = new Entry();
        e.seq = nextSeq++;
        e.time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        e.player = player;
        e.action = action;
        e.id = id;
        e.json = json == null ? "" : json;
        entries.add(e);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
        save();
    }

    /** Newest first. */
    public static synchronized List<Entry> recent(int max) {
        load();
        List<Entry> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && out.size() < max; i--) {
            out.add(entries.get(i));
        }
        return out;
    }

    public static synchronized Entry bySeq(long seq) {
        load();
        for (Entry e : entries) {
            if (e.seq == seq) {
                return e;
            }
        }
        return null;
    }
}
