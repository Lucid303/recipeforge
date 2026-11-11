package dev.recipeforge;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/**
 * Structure Forge (server side): rewrites the list of biomes a structure can spawn in, in the overlay pack.
 * Minecraft only reads this when a world loads, so it needs leaving and re-entering the world.
 */
public final class StructureServer {
    private StructureServer() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String STRUCT_DIR = "worldgen/structure";

    public static boolean handle(String action, ServerPlayer player, MinecraftServer server, String id, String data) {
        try {
            switch (action) {
                case "STRUCT_LIST" -> list(player, server);
                case "STRUCT_GET" -> getStructure(player, server, id);
                case "STRUCT_SET_BIOMES" -> setBiomes(player, server, id, data);
                case "STRUCT_RESTORE" -> restoreStructure(player, id);
                default -> {
                    return false;
                }
            }
        } catch (Exception e) {
            WorldForge.LOGGER.error("World Forge structure error", e);
            ServerHandler.fail(player, "Structure error: " + e.getMessage());
        }
        return true;
    }

    private static ResourceLocation structFile(ResourceLocation id) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), STRUCT_DIR + "/" + id.getPath() + ".json");
    }

    private static Path overlay(ResourceLocation id) {
        Path base = PackSetup.packDir().toAbsolutePath().normalize();
        Path file = base.resolve("data").resolve(id.getNamespace()).resolve(STRUCT_DIR)
                .resolve(id.getPath() + ".json").normalize();
        return file.startsWith(base) ? file : null;
    }

    private static void list(ServerPlayer player, MinecraftServer server) {
        List<String> structures = new ArrayList<>();
        for (ResourceLocation rl : server.getResourceManager()
                .listResources(STRUCT_DIR, p -> p.getPath().endsWith(".json")).keySet()) {
            String path = rl.getPath();
            structures.add(rl.getNamespace() + ":" + path.substring(STRUCT_DIR.length() + 1, path.length() - 5));
        }
        Collections.sort(structures);
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        structures.forEach(a::add);
        o.add("structures", a);
        ServerHandler.send(player, "STRUCT_LIST", "", o.toString());
    }

    private static String readText(MinecraftServer server, ResourceLocation file) {
        Optional<Resource> res = server.getResourceManager().getResource(file);
        if (res.isEmpty()) {
            return null;
        }
        try (BufferedReader reader = res.get().openAsReader()) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return null;
        }
    }

    private static void addBiomeRef(Registry<Biome> registry, String ref, TreeSet<String> out) {
        if (ref.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(ref.substring(1));
            if (tagId != null) {
                TagKey<Biome> tag = TagKey.create(Registries.BIOME, tagId);
                registry.getTag(tag).ifPresent(named -> named.forEach(holder ->
                        holder.unwrapKey().ifPresent(key -> out.add(key.location().toString()))));
            }
        } else {
            out.add(ref);
        }
    }

    private static List<String> biomesOf(MinecraftServer server, JsonObject structure) {
        TreeSet<String> out = new TreeSet<>();
        Registry<Biome> registry = server.registryAccess().registryOrThrow(Registries.BIOME);
        JsonElement b = structure.get("biomes");
        if (b != null && b.isJsonPrimitive()) {
            addBiomeRef(registry, b.getAsString(), out);
        } else if (b != null && b.isJsonArray()) {
            for (JsonElement e : b.getAsJsonArray()) {
                addBiomeRef(registry, e.getAsString(), out);
            }
        }
        return new ArrayList<>(out);
    }

    private static void getStructure(ServerPlayer player, MinecraftServer server, String idText) {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        String text = id == null ? null : readText(server, structFile(id));
        if (text == null) {
            ServerHandler.send(player, "STRUCT_ERROR", idText, "That structure could not be found.");
            return;
        }
        JsonArray array = new JsonArray();
        biomesOf(server, JsonParser.parseString(text).getAsJsonObject()).forEach(array::add);
        ServerHandler.send(player, "STRUCT", idText, array.toString());
    }

    private static void setBiomes(ServerPlayer player, MinecraftServer server, String idText, String data) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Path file = id == null ? null : overlay(id);
        String text = id == null ? null : readText(server, structFile(id));
        if (file == null || text == null) {
            ServerHandler.fail(player, "That structure could not be found.");
            return;
        }
        JsonArray chosen = JsonParser.parseString(data).getAsJsonArray();
        Registry<Biome> registry = server.registryAccess().registryOrThrow(Registries.BIOME);
        for (JsonElement e : chosen) {
            ResourceLocation rl = ResourceLocation.tryParse(e.getAsString());
            if (rl == null || !registry.containsKey(rl)) {
                ServerHandler.fail(player, "Unknown biome: " + e.getAsString());
                return;
            }
        }
        JsonObject structure = JsonParser.parseString(text).getAsJsonObject();
        JsonArray before = new JsonArray();
        biomesOf(server, structure).forEach(before::add);
        structure.add("biomes", chosen);
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(structure));
        History.add(player.getGameProfile().getName(), "STRUCT_SET_BIOMES", id.toString(),
                before.toString(), chosen.toString());
        ServerHandler.ok(player, "Saved. Leave and re-enter the world for " + id
                + " to use the new biomes (only newly generated chunks change).");
    }

    private static void restoreStructure(ServerPlayer player, String idText) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Path file = id == null ? null : overlay(id);
        if (file == null || !Files.deleteIfExists(file)) {
            ServerHandler.fail(player, "There is no edit of that structure to undo.");
            return;
        }
        History.add(player.getGameProfile().getName(), "STRUCT_RESTORE", id.toString(), "", "");
        ServerHandler.ok(player, "Put back the original biomes of " + id + ". Re-enter the world to apply.");
    }
}
