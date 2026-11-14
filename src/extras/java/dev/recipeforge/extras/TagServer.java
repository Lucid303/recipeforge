package dev.recipeforge.extras;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.recipeforge.History;
import dev.recipeforge.PackSetup;
import dev.recipeforge.ServerHandler;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;

/**
 * Tag Forge (server side): add or remove members of item, block and entity tags.
 * Changes are written as small tag files in the overlay pack ("values" to add, "remove" to take away)
 * and applied with a reload, so other mods' additions to the same tag keep working.
 */
public final class TagServer {
    private TagServer() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static boolean handle(String action, ServerPlayer player, MinecraftServer server, String id, String data) {
        switch (action) {
            case "TAG_EDIT" -> edit(player, server, id, data);
            case "TAG_RESTORE" -> restore(player, server, id);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static Registry<?> registryOf(String name) {
        return switch (name) {
            case "item" -> BuiltInRegistries.ITEM;
            case "block" -> BuiltInRegistries.BLOCK;
            case "entity_type" -> BuiltInRegistries.ENTITY_TYPE;
            default -> null;
        };
    }

    private static <T> List<String> membersOf(Registry<T> registry, ResourceLocation tagId) {
        List<String> out = new ArrayList<>();
        registry.getTag(TagKey.create(registry.key(), tagId)).ifPresent(named -> named.forEach(holder ->
                holder.unwrapKey().ifPresent(key -> out.add(key.location().toString()))));
        return out;
    }

    private static String membersJson(String registry, Set<String> members) {
        JsonObject o = new JsonObject();
        o.addProperty("registry", registry);
        JsonArray array = new JsonArray();
        int n = 0;
        for (String m : members) {
            if (n++ >= 120) {
                break;
            }
            array.add(m);
        }
        o.add("members", array);
        return o.toString();
    }

    private static Path overlay(String registry, ResourceLocation tag) {
        Path base = PackSetup.packDir().toAbsolutePath().normalize();
        Path file = base.resolve("data").resolve(tag.getNamespace()).resolve("tags").resolve(registry)
                .resolve(tag.getPath() + ".json").normalize();
        return file.startsWith(base) ? file : null;
    }

    private static void edit(ServerPlayer player, MinecraftServer server, String idText, String data) {
        try {
            String[] parts = idText.split("\\|", 2);
            Registry<?> registry = parts.length == 2 ? registryOf(parts[0]) : null;
            ResourceLocation tag = parts.length == 2 ? ResourceLocation.tryParse(parts[1]) : null;
            Path file = registry == null || tag == null ? null : overlay(parts[0], tag);
            if (file == null) {
                ServerHandler.fail(player, "Not a valid tag: " + idText);
                return;
            }
            JsonObject req = JsonParser.parseString(data).getAsJsonObject();
            List<String> add = new ArrayList<>();
            List<String> remove = new ArrayList<>();
            for (JsonElement e : req.getAsJsonArray("add")) {
                add.add(e.getAsString());
            }
            for (JsonElement e : req.getAsJsonArray("remove")) {
                remove.add(e.getAsString());
            }
            for (String s : add) {
                ResourceLocation rl = ResourceLocation.tryParse(s);
                if (rl == null || !registry.containsKey(rl)) {
                    ServerHandler.fail(player, "Unknown entry: " + s);
                    return;
                }
            }

            Set<String> before = new TreeSet<>(membersOf(registry, tag));
            LinkedHashSet<String> added = new LinkedHashSet<>();
            LinkedHashSet<String> removed = new LinkedHashSet<>();
            if (Files.exists(file)) {
                JsonObject old = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (old.has("values")) {
                    old.getAsJsonArray("values").forEach(v -> added.add(v.getAsString()));
                }
                if (old.has("remove")) {
                    old.getAsJsonArray("remove").forEach(v -> removed.add(v.getAsString()));
                }
            }
            for (String a : add) {
                removed.remove(a);
                added.add(a);
            }
            for (String r : remove) {
                added.remove(r);
                removed.add(r);
            }
            JsonObject out = new JsonObject();
            out.addProperty("replace", false);
            JsonArray values = new JsonArray();
            added.forEach(values::add);
            JsonArray removes = new JsonArray();
            removed.forEach(removes::add);
            out.add("values", values);
            out.add("remove", removes);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(out));

            Set<String> after = new TreeSet<>(before);
            after.addAll(add);
            after.removeAll(remove);
            History.add(player.getGameProfile().getName(), "TAG_EDIT", idText,
                    membersJson(parts[0], before), membersJson(parts[0], after));
            ServerHandler.ok(player, "Saved tag " + tag + ". Reloading...");
            ServerHandler.reload(server);
        } catch (Exception e) {
            ServerHandler.fail(player, "Tag error: " + e.getMessage());
        }
    }

    private static void restore(ServerPlayer player, MinecraftServer server, String idText) {
        try {
            String[] parts = idText.split("\\|", 2);
            ResourceLocation tag = parts.length == 2 ? ResourceLocation.tryParse(parts[1]) : null;
            Path file = tag == null || registryOf(parts[0]) == null ? null : overlay(parts[0], tag);
            if (file == null || !Files.deleteIfExists(file)) {
                ServerHandler.fail(player, "There is no edit of that tag to undo.");
                return;
            }
            History.add(player.getGameProfile().getName(), "TAG_RESTORE", idText, "", "");
            ServerHandler.ok(player, "Put back the original tag " + tag + ". Reloading...");
            ServerHandler.reload(server);
        } catch (IOException e) {
            ServerHandler.fail(player, "File error: " + e.getMessage());
        }
    }
}
