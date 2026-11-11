package dev.recipeforge;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Biome Forge (server side): remembers the colour changes (config/worldforge/biome_colors.json)
 * and tells every player. The colours themselves are applied on each client (see BiomeClient).
 */
public final class BiomeServer {
    private BiomeServer() {}

    public static final List<String> SLOTS = List.of("grass", "foliage", "water", "water_fog", "sky", "fog");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();
    private static Map<String, Map<String, Integer>> store = null;

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("worldforge").resolve("biome_colors.json");
    }

    private static synchronized Map<String, Map<String, Integer>> get() {
        if (store == null) {
            store = new TreeMap<>();
            Path f = file();
            if (Files.exists(f)) {
                try {
                    Map<String, Map<String, Integer>> loaded = GSON.fromJson(Files.readString(f), TYPE);
                    if (loaded != null) {
                        store.putAll(loaded);
                    }
                } catch (Exception e) {
                    WorldForge.LOGGER.error("World Forge could not read biome_colors.json", e);
                }
            }
        }
        return store;
    }

    private static synchronized void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(get()));
        } catch (IOException e) {
            WorldForge.LOGGER.error("World Forge could not write biome_colors.json", e);
        }
    }

    public static synchronized String toJson() {
        return GSON.toJson(get());
    }

    public static void sendTo(ServerPlayer player) {
        ServerHandler.send(player, "BIOME_SYNC", "", toJson());
    }

    /** The colour a biome really has right now (used by both server and client). */
    public static int effective(Biome biome, String slot) {
        return switch (slot) {
            case "grass" -> biome.getGrassColor(0.0, 0.0);
            case "foliage" -> biome.getFoliageColor();
            case "water" -> biome.getWaterColor();
            case "water_fog" -> biome.getWaterFogColor();
            case "sky" -> biome.getSkyColor();
            default -> biome.getFogColor();
        } & 0xFFFFFF;
    }

    public static boolean handle(String action, ServerPlayer player, MinecraftServer server, String id, String data) {
        switch (action) {
            case "GET_BIOME" -> sendTo(player);
            case "BIOME_SET" -> set(player, server, id, data);
            case "BIOME_RESET" -> reset(player, id);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void broadcast() {
        PacketDistributor.sendToAllPlayers(new ServerMessagePayload("BIOME_SYNC", "", toJson()));
    }

    private static void set(ServerPlayer player, MinecraftServer server, String id, String data) {
        try {
            JsonObject o = JsonParser.parseString(data).getAsJsonObject();
            String slot = o.get("slot").getAsString();
            int color = o.get("color").getAsInt() & 0xFFFFFF;
            ResourceLocation rl = ResourceLocation.tryParse(id);
            Registry<Biome> registry = server.registryAccess().registryOrThrow(Registries.BIOME);
            Biome biome = rl == null ? null : registry.getOptional(rl).orElse(null);
            if (biome == null || !SLOTS.contains(slot)) {
                ServerHandler.fail(player, "Unknown biome or colour slot.");
                return;
            }
            int before;
            synchronized (BiomeServer.class) {
                Map<String, Integer> mine = get().get(id);
                before = mine != null && mine.containsKey(slot) ? mine.get(slot) : effective(biome, slot);
                get().computeIfAbsent(id, k -> new HashMap<>()).put(slot, color);
            }
            save();
            JsonObject b = new JsonObject();
            b.addProperty("slot", slot);
            b.addProperty("color", before);
            JsonObject a = new JsonObject();
            a.addProperty("slot", slot);
            a.addProperty("color", color);
            History.add(player.getGameProfile().getName(), "BIOME_SET", id + " (" + slot + ")", b.toString(), a.toString());
            broadcast();
            ServerHandler.ok(player, "Changed the " + slot + " colour of " + id + ".");
        } catch (Exception e) {
            ServerHandler.fail(player, "Could not change the colour: " + e.getMessage());
        }
    }

    private static void reset(ServerPlayer player, String id) {
        String before;
        synchronized (BiomeServer.class) {
            if (id == null || id.isEmpty()) {
                before = GSON.toJson(get());
                get().clear();
            } else {
                Map<String, Integer> removed = get().remove(id);
                before = removed == null ? "" : GSON.toJson(removed);
            }
        }
        save();
        History.add(player.getGameProfile().getName(), "BIOME_RESET", (id == null || id.isEmpty()) ? "all biomes" : id,
                before, "");
        broadcast();
        ServerHandler.ok(player, "Colours put back to normal.");
    }
}
