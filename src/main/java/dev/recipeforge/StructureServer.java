package dev.recipeforge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Structure Forge (server side).
 * - Where a structure spawns: rewrites the structure's biome list in the overlay pack.
 *   Minecraft only reads this when a world loads, so it needs leaving and re-entering the world.
 * - Structure blocks: replaces one block with another inside a structure template file (.nbt).
 *   Templates reload with /reload and affect structures that generate afterwards.
 */
public final class StructureServer {
    private StructureServer() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String STRUCT_DIR = "worldgen/structure";
    private static final String TEMPLATE_DIR = "structure";

    public static boolean handle(String action, ServerPlayer player, MinecraftServer server, String id, String data) {
        try {
            switch (action) {
                case "STRUCT_LIST" -> list(player, server);
                case "STRUCT_GET" -> getStructure(player, server, id);
                case "STRUCT_SET_BIOMES" -> setBiomes(player, server, id, data);
                case "STRUCT_RESTORE" -> restoreStructure(player, id);
                case "TEMPLATE_GET" -> getTemplate(player, server, id);
                case "TEMPLATE_REPLACE" -> replaceBlock(player, server, id, data);
                case "TEMPLATE_RESTORE" -> restoreTemplate(player, server, id);
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

    // ------------------------------------------------------------ paths

    private static ResourceLocation structFile(ResourceLocation id) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), STRUCT_DIR + "/" + id.getPath() + ".json");
    }

    private static ResourceLocation templateFile(ResourceLocation id) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), TEMPLATE_DIR + "/" + id.getPath() + ".nbt");
    }

    private static Path overlay(ResourceLocation id, String dir, String extension) {
        Path base = PackSetup.packDir().toAbsolutePath().normalize();
        Path file = base.resolve("data").resolve(id.getNamespace()).resolve(dir)
                .resolve(id.getPath() + extension).normalize();
        return file.startsWith(base) ? file : null;
    }

    // ------------------------------------------------------------ list

    private static void list(ServerPlayer player, MinecraftServer server) {
        List<String> structures = new ArrayList<>();
        for (ResourceLocation rl : server.getResourceManager()
                .listResources(STRUCT_DIR, p -> p.getPath().endsWith(".json")).keySet()) {
            String path = rl.getPath();
            structures.add(rl.getNamespace() + ":" + path.substring(STRUCT_DIR.length() + 1, path.length() - 5));
        }
        List<String> templates = new ArrayList<>();
        for (ResourceLocation rl : server.getResourceManager()
                .listResources(TEMPLATE_DIR, p -> p.getPath().endsWith(".nbt")).keySet()) {
            String path = rl.getPath();
            templates.add(rl.getNamespace() + ":" + path.substring(TEMPLATE_DIR.length() + 1, path.length() - 4));
        }
        Collections.sort(structures);
        Collections.sort(templates);
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        structures.forEach(a::add);
        JsonArray b = new JsonArray();
        templates.forEach(b::add);
        o.add("structures", a);
        o.add("templates", b);
        ServerHandler.send(player, "STRUCT_LIST", "", o.toString());
    }

    // ------------------------------------------------------------ biomes of a structure

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
        Path file = id == null ? null : overlay(id, STRUCT_DIR, ".json");
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
        Path file = id == null ? null : overlay(id, STRUCT_DIR, ".json");
        if (file == null || !Files.deleteIfExists(file)) {
            ServerHandler.fail(player, "There is no edit of that structure to undo.");
            return;
        }
        History.add(player.getGameProfile().getName(), "STRUCT_RESTORE", id.toString(), "", "");
        ServerHandler.ok(player, "Put back the original biomes of " + id + ". Re-enter the world to apply.");
    }

    // ------------------------------------------------------------ template blocks

    private static CompoundTag readNbt(MinecraftServer server, ResourceLocation id) throws IOException {
        Optional<Resource> res = server.getResourceManager().getResource(templateFile(id));
        if (res.isEmpty()) {
            return null;
        }
        try (InputStream in = res.get().open()) {
            return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
    }

    private static List<ListTag> paletteLists(CompoundTag tag) {
        List<ListTag> lists = new ArrayList<>();
        if (tag.contains("palette", Tag.TAG_LIST)) {
            lists.add(tag.getList("palette", Tag.TAG_COMPOUND));
        }
        if (tag.contains("palettes", Tag.TAG_LIST)) {
            ListTag all = tag.getList("palettes", Tag.TAG_LIST);
            for (int i = 0; i < all.size(); i++) {
                lists.add(all.getList(i));
            }
        }
        return lists;
    }

    private static void getTemplate(ServerPlayer player, MinecraftServer server, String idText) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        CompoundTag tag = id == null ? null : readNbt(server, id);
        if (tag == null) {
            ServerHandler.send(player, "TEMPLATE_ERROR", idText, "That structure file could not be found.");
            return;
        }
        List<ListTag> palettes = paletteLists(tag);
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (!palettes.isEmpty()) {
            ListTag palette = palettes.get(0);
            ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
            for (int i = 0; i < blocks.size(); i++) {
                int state = blocks.getCompound(i).getInt("state");
                if (state >= 0 && state < palette.size()) {
                    counts.merge(palette.getCompound(state).getString("Name"), 1, Integer::sum);
                }
            }
        }
        JsonObject o = new JsonObject();
        ListTag size = tag.getList("size", Tag.TAG_INT);
        JsonArray sz = new JsonArray();
        for (int i = 0; i < size.size(); i++) {
            sz.add(size.getInt(i));
        }
        o.add("size", sz);
        JsonArray pal = new JsonArray();
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> {
            JsonObject p = new JsonObject();
            p.addProperty("name", e.getKey());
            p.addProperty("count", e.getValue());
            pal.add(p);
        });
        o.add("palette", pal);
        ServerHandler.send(player, "TEMPLATE", idText, o.toString());
    }

    private static void replaceBlock(ServerPlayer player, MinecraftServer server, String idText, String data) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Path file = id == null ? null : overlay(id, TEMPLATE_DIR, ".nbt");
        CompoundTag tag = id == null ? null : readNbt(server, id);
        if (file == null || tag == null) {
            ServerHandler.fail(player, "That structure file could not be found.");
            return;
        }
        JsonObject req = JsonParser.parseString(data).getAsJsonObject();
        String from = req.get("from").getAsString();
        String to = req.get("to").getAsString();
        ResourceLocation toId = ResourceLocation.tryParse(to);
        if (toId == null || !BuiltInRegistries.BLOCK.containsKey(toId)) {
            ServerHandler.fail(player, "Unknown block: " + to);
            return;
        }
        Block toBlock = BuiltInRegistries.BLOCK.getOptional(toId).orElse(Blocks.AIR);
        int changed = 0;
        for (ListTag palette : paletteLists(tag)) {
            for (int i = 0; i < palette.size(); i++) {
                CompoundTag entry = palette.getCompound(i);
                if (!entry.getString("Name").equals(from)) {
                    continue;
                }
                entry.putString("Name", to);
                if (entry.contains("Properties", Tag.TAG_COMPOUND)) {
                    CompoundTag props = entry.getCompound("Properties");
                    for (String key : new ArrayList<>(props.getAllKeys())) {
                        if (toBlock.getStateDefinition().getProperty(key) == null) {
                            props.remove(key);
                        }
                    }
                }
                changed++;
            }
        }
        if (changed == 0) {
            ServerHandler.fail(player, from + " is not used in that structure.");
            return;
        }
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(tag, file);
        History.add(player.getGameProfile().getName(), "TEMPLATE_REPLACE", id.toString(), from, to);
        ServerHandler.ok(player, "Replaced " + from + " with " + to + " in " + id + ". Reloading...");
        ServerHandler.reload(server);
    }

    private static void restoreTemplate(ServerPlayer player, MinecraftServer server, String idText) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Path file = id == null ? null : overlay(id, TEMPLATE_DIR, ".nbt");
        if (file == null || !Files.deleteIfExists(file)) {
            ServerHandler.fail(player, "There is no edit of that structure file to undo.");
            return;
        }
        History.add(player.getGameProfile().getName(), "TEMPLATE_RESTORE", id.toString(), "", "");
        ServerHandler.ok(player, "Put back the original " + id + ". Reloading...");
        ServerHandler.reload(server);
    }
}
