package dev.recipeforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.entity.BannerPattern;

/**
 * Loom: which banner pattern needs which pattern item is stored in tag files.
 * We rewrite those tag files in the overlay pack and reload.
 */
public final class LoomServer {
    private LoomServer() {}

    public static final List<String> SOURCES =
            List.of("none", "flower", "creeper", "skull", "mojang", "globe", "piglin", "flow", "guster");

    public static String tagPath(String source) {
        return source.equals("none") ? "no_item_required" : "pattern_item/" + source;
    }

    private static Path file(String source) {
        return PackSetup.packDir().resolve("data").resolve("minecraft").resolve("tags")
                .resolve("banner_pattern").resolve(tagPath(source) + ".json");
    }

    /** Returns null on success or an error message. */
    public static String set(MinecraftServer server, String patternText, String source) throws IOException {
        ResourceLocation pattern = ResourceLocation.tryParse(patternText);
        if (pattern == null) {
            return "Not a valid pattern id.";
        }
        if (!SOURCES.contains(source)) {
            return "Unknown pattern item: " + source;
        }
        Registry<BannerPattern> registry = server.registryAccess().registryOrThrow(Registries.BANNER_PATTERN);
        if (!registry.containsKey(pattern)) {
            return "Unknown banner pattern: " + pattern;
        }

        Map<String, List<String>> members = new LinkedHashMap<>();
        for (String src : SOURCES) {
            List<String> list = new ArrayList<>();
            TagKey<BannerPattern> tag = TagKey.create(Registries.BANNER_PATTERN,
                    ResourceLocation.withDefaultNamespace(tagPath(src)));
            registry.getTag(tag).ifPresent(named -> named.forEach(holder ->
                    holder.unwrapKey().ifPresent(key -> list.add(key.location().toString()))));
            members.put(src, list);
        }
        for (List<String> list : members.values()) {
            list.remove(pattern.toString());
        }
        members.get(source).add(pattern.toString());

        for (Map.Entry<String, List<String>> e : members.entrySet()) {
            StringBuilder sb = new StringBuilder("{\"replace\":true,\"values\":[");
            boolean first = true;
            for (String value : e.getValue()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append('"').append(value).append('"');
                first = false;
            }
            sb.append("]}");
            Path f = file(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, sb.toString());
        }
        return null;
    }

    public static void reset() throws IOException {
        for (String src : SOURCES) {
            Files.deleteIfExists(file(src));
        }
    }
}
