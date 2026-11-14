package dev.recipeforge.extras;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.recipeforge.History;
import dev.recipeforge.PackSetup;
import dev.recipeforge.ServerHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;

/**
 * Enchantment Forge and World Gen Forge (server side).
 * Both edit data files (enchantments, placed and configured features). Minecraft reads these
 * when a world loads, so changes need leaving and re-entering the world.
 */
public final class DataServer {
    private DataServer() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ENCH_DIR = "enchantment";
    private static final String PLACED_DIR = "worldgen/placed_feature";
    private static final String CONFIGURED_DIR = "worldgen/configured_feature";

    public static boolean handle(String action, ServerPlayer player, MinecraftServer server, String id, String data) {
        try {
            switch (action) {
                case "ENCH_LIST" -> list(player, server, ENCH_DIR, "ENCH_LIST");
                case "ENCH_GET" -> enchGet(player, server, id);
                case "ENCH_SET" -> enchSet(player, server, id, data);
                case "ENCH_RESTORE" -> restore(player, id, ENCH_DIR, null, "ENCH_RESTORE");
                case "GEN_LIST" -> list(player, server, PLACED_DIR, "GEN_LIST");
                case "GEN_GET" -> genGet(player, server, id);
                case "GEN_SET" -> genSet(player, server, id, data);
                case "GEN_RESTORE" -> restore(player, id, PLACED_DIR, server, "GEN_RESTORE");
                default -> {
                    return false;
                }
            }
        } catch (Exception e) {
            ServerHandler.fail(player, "Error: " + e.getMessage());
        }
        return true;
    }

    // ------------------------------------------------------------ files

    private static ResourceLocation fileLoc(ResourceLocation id, String dir) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), dir + "/" + id.getPath() + ".json");
    }

    private static Path overlay(ResourceLocation id, String dir) {
        Path base = PackSetup.packDir().toAbsolutePath().normalize();
        Path file = base.resolve("data").resolve(id.getNamespace()).resolve(dir)
                .resolve(id.getPath() + ".json").normalize();
        return file.startsWith(base) ? file : null;
    }

    private static String read(MinecraftServer server, ResourceLocation id, String dir) {
        Optional<Resource> res = server.getResourceManager().getResource(fileLoc(id, dir));
        if (res.isEmpty()) {
            return null;
        }
        try (BufferedReader reader = res.get().openAsReader()) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return null;
        }
    }

    private static void list(ServerPlayer player, MinecraftServer server, String dir, String kind) {
        List<String> ids = new ArrayList<>();
        for (ResourceLocation rl : server.getResourceManager().listResources(dir, p -> p.getPath().endsWith(".json")).keySet()) {
            String path = rl.getPath();
            ids.add(rl.getNamespace() + ":" + path.substring(dir.length() + 1, path.length() - 5));
        }
        Collections.sort(ids);
        JsonArray array = new JsonArray();
        ids.forEach(array::add);
        ServerHandler.send(player, kind, "", array.toString());
    }

    private static void write(Path file, JsonObject obj) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(obj));
    }

    // ------------------------------------------------------------ small helpers for summaries

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    private static void put(JsonObject out, String key, JsonObject o, String field) {
        if (o != null && o.has(field) && o.get(field).isJsonPrimitive()) {
            out.add(key, o.get(field));
        }
    }

    private static String enchSummary(String text) {
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            JsonObject s = new JsonObject();
            put(s, "max_level", o, "max_level");
            put(s, "weight", o, "weight");
            put(s, "anvil_cost", o, "anvil_cost");
            put(s, "min_cost_base", obj(o, "min_cost"), "base");
            put(s, "min_cost_per_level", obj(o, "min_cost"), "per_level_above_first");
            put(s, "max_cost_base", obj(o, "max_cost"), "base");
            put(s, "max_cost_per_level", obj(o, "max_cost"), "per_level_above_first");
            if (o.has("supported_items")) {
                s.addProperty("items", o.get("supported_items").toString());
            }
            return s.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static JsonObject modifier(JsonObject placed, String type) {
        if (placed == null || !placed.has("placement")) {
            return null;
        }
        for (JsonElement e : placed.getAsJsonArray("placement")) {
            if (e.isJsonObject() && e.getAsJsonObject().has("type")
                    && e.getAsJsonObject().get("type").getAsString().endsWith(type)) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    private static String genSummary(JsonObject placed, JsonObject configured) {
        try {
            JsonObject s = new JsonObject();
            JsonObject count = modifier(placed, "count");
            if (count != null && count.has("count")) {
                s.addProperty("count", count.get("count").isJsonPrimitive() ? count.get("count").getAsString() : "(complex)");
            }
            JsonObject rarity = modifier(placed, "rarity_filter");
            put(s, "rarity_1_in", rarity, "chance");
            JsonObject height = modifier(placed, "height_range");
            JsonObject h = obj(height, "height");
            for (String side : new String[] {"min_inclusive", "max_inclusive"}) {
                JsonObject b = obj(h, side);
                if (b != null && !b.entrySet().isEmpty()) {
                    var first = b.entrySet().iterator().next();
                    s.addProperty(side.startsWith("min") ? "min_height" : "max_height",
                            first.getKey() + " " + first.getValue().getAsString());
                }
            }
            put(s, "vein_size", obj(configured, "config"), "size");
            return s.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------ enchantments

    private static void enchGet(ServerPlayer player, MinecraftServer server, String idText) {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        String text = id == null ? null : read(server, id, ENCH_DIR);
        if (text == null) {
            ServerHandler.send(player, "DATA_ERROR", idText, "That enchantment could not be found.");
        } else {
            ServerHandler.send(player, "ENCH", idText, text);
        }
    }

    private static void enchSet(ServerPlayer player, MinecraftServer server, String idText, String data) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Path file = id == null ? null : overlay(id, ENCH_DIR);
        if (file == null) {
            ServerHandler.fail(player, "Not a valid enchantment id.");
            return;
        }
        JsonObject o = JsonParser.parseString(data).getAsJsonObject();
        String before = read(server, id, ENCH_DIR);
        write(file, o);
        History.add(player.getGameProfile().getName(), "ENCH_SET", id.toString(),
                before == null ? "" : enchSummary(before), enchSummary(GSON.toJson(o)));
        ServerHandler.ok(player, "Saved. Leave and re-enter the world for " + id + " to change.");
    }

    // ------------------------------------------------------------ world generation

    private static void genGet(ServerPlayer player, MinecraftServer server, String idText) {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        String text = id == null ? null : read(server, id, PLACED_DIR);
        if (text == null) {
            ServerHandler.send(player, "DATA_ERROR", idText, "That feature could not be found.");
            return;
        }
        JsonObject placed = JsonParser.parseString(text).getAsJsonObject();
        JsonObject reply = new JsonObject();
        reply.add("placed", placed);
        if (placed.has("feature") && placed.get("feature").isJsonPrimitive()) {
            String cfgId = placed.get("feature").getAsString();
            ResourceLocation cfg = ResourceLocation.tryParse(cfgId);
            String cfgText = cfg == null ? null : read(server, cfg, CONFIGURED_DIR);
            if (cfgText != null) {
                reply.addProperty("configuredId", cfgId);
                reply.add("configured", JsonParser.parseString(cfgText));
            }
        }
        ServerHandler.send(player, "GEN", idText, reply.toString());
    }

    private static void genSet(ServerPlayer player, MinecraftServer server, String idText, String data) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Path file = id == null ? null : overlay(id, PLACED_DIR);
        if (file == null) {
            ServerHandler.fail(player, "Not a valid feature id.");
            return;
        }
        JsonObject req = JsonParser.parseString(data).getAsJsonObject();
        JsonObject placed = req.getAsJsonObject("placed");
        String beforePlacedText = read(server, id, PLACED_DIR);
        JsonObject beforePlaced = beforePlacedText == null ? null : JsonParser.parseString(beforePlacedText).getAsJsonObject();
        JsonObject beforeConfigured = null;
        JsonObject configured = req.has("configured") ? req.getAsJsonObject("configured") : null;
        ResourceLocation cfgId = req.has("configuredId") ? ResourceLocation.tryParse(req.get("configuredId").getAsString()) : null;
        if (cfgId != null) {
            String cfgText = read(server, cfgId, CONFIGURED_DIR);
            beforeConfigured = cfgText == null ? null : JsonParser.parseString(cfgText).getAsJsonObject();
        }
        write(file, placed);
        if (configured != null && cfgId != null) {
            Path cfgFile = overlay(cfgId, CONFIGURED_DIR);
            if (cfgFile != null) {
                write(cfgFile, configured);
            }
        }
        History.add(player.getGameProfile().getName(), "GEN_SET", id.toString(),
                genSummary(beforePlaced, beforeConfigured), genSummary(placed, configured));
        ServerHandler.ok(player, "Saved. Leave and re-enter the world for " + id + " to change (new chunks only).");
    }

    private static void restore(ServerPlayer player, String idText, String dir, MinecraftServer server, String action) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Path file = id == null ? null : overlay(id, dir);
        if (file == null) {
            ServerHandler.fail(player, "Not a valid id.");
            return;
        }
        boolean removed = Files.deleteIfExists(file);
        if (server != null && removed) {
            // also put back the vein size file of this feature, if there was one
            try {
                JsonObject placed = JsonParser.parseString(read(server, id, PLACED_DIR)).getAsJsonObject();
                if (placed.has("feature") && placed.get("feature").isJsonPrimitive()) {
                    ResourceLocation cfg = ResourceLocation.tryParse(placed.get("feature").getAsString());
                    Path cfgFile = cfg == null ? null : overlay(cfg, CONFIGURED_DIR);
                    if (cfgFile != null) {
                        Files.deleteIfExists(cfgFile);
                    }
                }
            } catch (Exception ignored) {
                // nothing else to put back
            }
        }
        if (!removed) {
            ServerHandler.fail(player, "There is no edit of that to undo.");
            return;
        }
        History.add(player.getGameProfile().getName(), action, id.toString(), "", "");
        ServerHandler.ok(player, "Put back the original " + id + ". Re-enter the world to apply.");
    }
}
