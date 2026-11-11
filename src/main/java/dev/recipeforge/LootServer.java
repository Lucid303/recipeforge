package dev.recipeforge;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;

/**
 * Loot Forge (server side). Loot tables are plain data files, so every mod's tables work.
 * Edits are written to the overlay pack and applied with a reload.
 */
public final class LootServer {
    private LootServer() {}

    private static final String DIR = "loot_table";
    private static final String DISABLED_KEY = "worldforge_disabled";
    private static final String PREVIOUS_KEY = "worldforge_previous";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static boolean handle(String action, ServerPlayer player, MinecraftServer server, String id, String data) {
        switch (action) {
            case "LOOT_LIST" -> list(player, server);
            case "LOOT_GET" -> get(player, server, id);
            case "LOOT_SAVE" -> save(player, server, id, data);
            case "LOOT_DISABLE" -> disable(player, server, id);
            case "LOOT_ENABLE" -> enable(player, server, id);
            case "LOOT_RESTORE" -> restore(player, server, id);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static ResourceLocation fileLoc(ResourceLocation id) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), DIR + "/" + id.getPath() + ".json");
    }

    private static Path overlayFile(ResourceLocation id) {
        Path base = PackSetup.packDir().toAbsolutePath().normalize();
        Path file = base.resolve("data").resolve(id.getNamespace()).resolve(DIR)
                .resolve(id.getPath() + ".json").normalize();
        return file.startsWith(base) ? file : null;
    }

    /** Current text of a loot table (our overlay wins over the mod's own file), or null. */
    private static String read(MinecraftServer server, ResourceLocation id) {
        Optional<Resource> res = server.getResourceManager().getResource(fileLoc(id));
        if (res.isEmpty()) {
            return null;
        }
        try (BufferedReader reader = res.get().openAsReader()) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return null;
        }
    }

    private static void list(ServerPlayer player, MinecraftServer server) {
        List<String> ids = new ArrayList<>();
        Map<ResourceLocation, Resource> found =
                server.getResourceManager().listResources(DIR, rl -> rl.getPath().endsWith(".json"));
        for (ResourceLocation rl : found.keySet()) {
            String path = rl.getPath();
            ids.add(rl.getNamespace() + ":" + path.substring(DIR.length() + 1, path.length() - 5));
        }
        Collections.sort(ids);
        JsonArray array = new JsonArray();
        for (String s : ids) {
            array.add(s);
        }
        ServerHandler.send(player, "LOOT_LIST", "", array.toString());
    }

    private static void get(ServerPlayer player, MinecraftServer server, String idText) {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        String text = id == null ? null : read(server, id);
        if (text == null) {
            ServerHandler.send(player, "LOOT_ERROR", idText, "That loot table could not be found.");
        } else {
            ServerHandler.send(player, "LOOT", idText, text);
        }
    }

    private static void save(ServerPlayer player, MinecraftServer server, String idText, String text) {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Path file = id == null ? null : overlayFile(id);
        if (file == null) {
            ServerHandler.fail(player, "Not a valid loot table id: " + idText);
            return;
        }
        JsonElement json;
        try {
            json = JsonParser.parseString(text);
        } catch (JsonParseException e) {
            ServerHandler.fail(player, "That is not valid JSON: " + e.getMessage());
            return;
        }
        if (!json.isJsonObject()) {
            ServerHandler.fail(player, "A loot table must be a JSON object { ... }.");
            return;
        }
        JsonObject obj = json.getAsJsonObject();
        if (obj.has("pools") && !obj.get("pools").isJsonArray()) {
            ServerHandler.fail(player, "\"pools\" must be a list.");
            return;
        }
        obj.remove(DISABLED_KEY);
        obj.remove(PREVIOUS_KEY);
        String before = read(server, id);
        String after = GSON.toJson(obj);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, after);
        } catch (IOException e) {
            ServerHandler.fail(player, "File error: " + e.getMessage());
            return;
        }
        History.add(player.getGameProfile().getName(), "LOOT_SAVE", id.toString(), before == null ? "" : before, after);
        ServerHandler.ok(player, "Saved loot table " + id + ". Reloading...");
        ServerHandler.reload(server);
    }

    private static boolean isDisabledFile(Path file) {
        try {
            if (!Files.exists(file)) {
                return false;
            }
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject().has(DISABLED_KEY);
        } catch (Exception e) {
            return false;
        }
    }

    private static void disable(ServerPlayer player, MinecraftServer server, String idText) {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Path file = id == null ? null : overlayFile(id);
        if (file == null) {
            ServerHandler.fail(player, "Not a valid loot table id: " + idText);
            return;
        }
        if (isDisabledFile(file)) {
            ServerHandler.fail(player, "That loot table is already turned off.");
            return;
        }
        String before = read(server, id);
        String type = "minecraft:generic";
        try {
            if (before != null) {
                JsonObject o = JsonParser.parseString(before).getAsJsonObject();
                if (o.has("type")) {
                    type = o.get("type").getAsString();
                }
            }
        } catch (Exception ignored) {
            // keep the generic type
        }
        try {
            String previous = Files.exists(file) ? Files.readString(file) : "";
            JsonObject off = new JsonObject();
            off.addProperty("type", type);
            off.add("pools", new JsonArray());
            off.addProperty(DISABLED_KEY, true);
            off.addProperty(PREVIOUS_KEY, previous);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(off));
        } catch (IOException e) {
            ServerHandler.fail(player, "File error: " + e.getMessage());
            return;
        }
        History.add(player.getGameProfile().getName(), "LOOT_DISABLE", id.toString(), before == null ? "" : before, "");
        ServerHandler.ok(player, "Loot table " + id + " is now turned off. Reloading...");
        ServerHandler.reload(server);
    }

    private static void enable(ServerPlayer player, MinecraftServer server, String idText) {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Path file = id == null ? null : overlayFile(id);
        if (file == null || !isDisabledFile(file)) {
            ServerHandler.fail(player, "That loot table is not turned off.");
            return;
        }
        String restored = "";
        try {
            JsonObject off = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            String previous = off.has(PREVIOUS_KEY) ? off.get(PREVIOUS_KEY).getAsString() : "";
            if (previous.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                Files.writeString(file, previous);
                restored = previous;
            }
        } catch (Exception e) {
            ServerHandler.fail(player, "File error: " + e.getMessage());
            return;
        }
        History.add(player.getGameProfile().getName(), "LOOT_ENABLE", id.toString(), "", restored);
        ServerHandler.ok(player, "Loot table " + id + " is turned back on. Reloading...");
        ServerHandler.reload(server);
    }

    /** Forget every saved change to this table. If nothing was saved, it just shows the table again. */
    private static void restore(ServerPlayer player, MinecraftServer server, String idText) {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Path file = id == null ? null : overlayFile(id);
        if (file == null) {
            ServerHandler.fail(player, "Not a valid loot table id: " + idText);
            return;
        }
        try {
            if (!Files.exists(file)) {
                ServerHandler.ok(player, "Nothing was saved, so this is already the original.");
                return;
            }
            String before = Files.readString(file);
            Files.deleteIfExists(file);
            History.add(player.getGameProfile().getName(), "LOOT_RESTORE", id.toString(), before, "");
        } catch (IOException e) {
            ServerHandler.fail(player, "File error: " + e.getMessage());
            return;
        }
        ServerHandler.ok(player, "Put back the original loot table " + id + ". Reloading...");
        ServerHandler.reload(server);
    }
}
