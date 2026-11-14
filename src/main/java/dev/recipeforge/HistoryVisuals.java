package dev.recipeforge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;

/** Draws the little "pictures" shown in the History screen for every kind of change. */
public final class HistoryVisuals {
    private HistoryVisuals() {}

    private record Recipe(String type, List<Ingredient> ings, int cols, ItemStack result) {}

    private static final Map<String, Recipe> RECIPES = new HashMap<>();
    private static final Map<String, List<ItemStack>> LOOT = new HashMap<>();

    // ------------------------------------------------------------ parsing

    private static Ingredient ingredient(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        RegistryOps<JsonElement> ops = mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        return Ingredient.CODEC.parse(ops, el).result().orElse(null);
    }

    private static ItemStack stackOf(String id, int count) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        return BuiltInRegistries.ITEM.getOptional(rl).map(i -> new ItemStack(i, Math.max(1, count))).orElse(ItemStack.EMPTY);
    }

    private static Recipe parseRecipe(String json) {
        List<Ingredient> ings = new ArrayList<>();
        int cols = 3;
        String type = "?";
        ItemStack result = ItemStack.EMPTY;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            type = o.has("type") ? o.get("type").getAsString() : "?";
            switch (type) {
                case "minecraft:crafting_shaped", "worldforge:keep_shaped" -> {
                    JsonArray pattern = o.getAsJsonArray("pattern");
                    JsonObject key = o.getAsJsonObject("key");
                    for (JsonElement row : pattern) {
                        String r = row.getAsString();
                        cols = Math.max(cols == 3 ? 0 : cols, r.length());
                    }
                    for (JsonElement row : pattern) {
                        String r = row.getAsString();
                        for (int c = 0; c < cols; c++) {
                            char ch = c < r.length() ? r.charAt(c) : ' ';
                            ings.add(ch == ' ' ? null : ingredient(key.get(String.valueOf(ch))));
                        }
                    }
                }
                case "minecraft:crafting_shapeless", "worldforge:keep_shapeless" -> {
                    for (JsonElement e : o.getAsJsonArray("ingredients")) {
                        ings.add(ingredient(e));
                    }
                }
                case "minecraft:smithing_transform" -> {
                    ings.add(ingredient(o.get("template")));
                    ings.add(ingredient(o.get("base")));
                    ings.add(ingredient(o.get("addition")));
                }
                default -> {
                    if (o.has("ingredient")) {
                        ings.add(ingredient(o.get("ingredient")));
                    }
                }
            }
            JsonElement res = o.get("result");
            if (res != null && res.isJsonObject()) {
                JsonObject ro = res.getAsJsonObject();
                result = stackOf(ro.get("id").getAsString(), ro.has("count") ? ro.get("count").getAsInt() : 1);
            } else if (res != null && res.isJsonPrimitive()) {
                result = stackOf(res.getAsString(), 1);
            }
        } catch (Exception ignored) {
            // leave what we have
        }
        return new Recipe(type, ings, Math.max(1, cols), result);
    }

    private static List<ItemStack> parseLoot(String json) {
        List<ItemStack> out = new ArrayList<>();
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.has("pools")) {
                for (JsonElement p : o.getAsJsonArray("pools")) {
                    JsonObject pool = p.getAsJsonObject();
                    if (!pool.has("entries")) {
                        continue;
                    }
                    for (JsonElement e : pool.getAsJsonArray("entries")) {
                        JsonObject entry = e.getAsJsonObject();
                        if (entry.has("name") && entry.has("type") && entry.get("type").getAsString().endsWith("item")) {
                            out.add(stackOf(entry.get("name").getAsString(), 1));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // leave what we have
        }
        return out;
    }

    // ------------------------------------------------------------ drawing

    /** Draws the picture of one version of a change inside the box at (x, y, w, h). */
    public static void draw(GuiGraphics g, Font font, String module, String action, String text,
                            int x, int y, int w, int h) {
        if (text == null || text.isEmpty()) {
            g.drawString(font, "(nothing)", x, y, 0x888888);
            return;
        }
        ItemStack hover = ItemStack.EMPTY;
        switch (module) {
            case "recipe" -> drawRecipe(g, font, text, x, y);
            case "loot" -> drawLoot(g, font, text, x, y, w);
            case "biome" -> drawBiome(g, font, text, x, y);
            case "structure" -> drawStructure(g, font, text, x, y, w);
            case "station" -> drawStation(g, font, text, x, y, w);
            case "tag" -> drawTag(g, font, text, x, y, w);
            case "enchantment", "worldgen" -> drawFlat(g, font, text, x, y, w);
            default -> drawText(g, font, text, x, y, w, h);
        }
    }

    private static void drawRecipe(GuiGraphics g, Font font, String text, int x, int y) {
        if (RECIPES.size() > 16) {
            RECIPES.clear();
        }
        Recipe r = RECIPES.computeIfAbsent(text, HistoryVisuals::parseRecipe);
        int cols = r.cols();
        int count = Math.min(r.ings().size(), 9);
        for (int i = 0; i < count; i++) {
            int cx = x + (i % cols) * 18;
            int cy = y + (i / cols) * 18;
            Ui.slot(g, cx, cy, 18);
            Ingredient ing = r.ings().get(i);
            if (ing != null) {
                ItemStack s = Ui.cycle(ing.getItems());
                if (!s.isEmpty()) {
                    g.renderItem(s, cx + 1, cy + 1);
                }
            }
        }
        int rx = x + Math.max(cols, 3) * 18 + 8;
        g.drawString(font, "->", rx, y + 10, 0xFFFFFF);
        Ui.slot(g, rx + 16, y + 9, 18);
        if (!r.result().isEmpty()) {
            g.renderItem(r.result(), rx + 17, y + 10);
            g.renderItemDecorations(font, r.result(), rx + 17, y + 10);
        }
        g.drawString(font, font.plainSubstrByWidth(r.type().replace("minecraft:", ""), 140), x, y + 60, 0xAAAAAA);
    }

    private static void drawLoot(GuiGraphics g, Font font, String text, int x, int y, int w) {
        if (LOOT.size() > 16) {
            LOOT.clear();
        }
        List<ItemStack> items = LOOT.computeIfAbsent(text, HistoryVisuals::parseLoot);
        int cols = Math.max(1, w / 18);
        for (int i = 0; i < items.size() && i < cols * 4; i++) {
            int cx = x + (i % cols) * 18;
            int cy = y + (i / cols) * 18;
            Ui.slot(g, cx, cy, 18);
            if (!items.get(i).isEmpty()) {
                g.renderItem(items.get(i), cx + 1, cy + 1);
            }
        }
        g.drawString(font, items.size() + " item entries", x, y + 76, 0xAAAAAA);
    }

    private static void drawBiome(GuiGraphics g, Font font, String text, int x, int y) {
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            int color = o.get("color").getAsInt() & 0xFFFFFF;
            g.fill(x, y, x + 44, y + 28, 0xFF000000);
            g.fill(x + 1, y + 1, x + 43, y + 27, 0xFF000000 | color);
            g.drawString(font, o.get("slot").getAsString().replace('_', ' '), x + 50, y + 4, 0xFFFFFF);
            g.drawString(font, String.format(Locale.ROOT, "#%06X", color), x + 50, y + 16, 0xAAAAAA);
        } catch (Exception e) {
            drawText(g, font, text, x, y, 120, 40);
        }
    }

    private static void drawStructure(GuiGraphics g, Font font, String text, int x, int y, int w) {
        try {
            JsonArray array = JsonParser.parseString(text).getAsJsonArray();
            Minecraft mc = Minecraft.getInstance();
            g.drawString(font, array.size() + " biomes", x, y, 0xFFFFFF);
            int cols = Math.max(1, w / 11);
            for (int i = 0; i < array.size() && i < cols * 6; i++) {
                int color = 0x888888;
                ResourceLocation rl = ResourceLocation.tryParse(array.get(i).getAsString());
                if (mc.level != null && rl != null) {
                    net.minecraft.world.level.biome.Biome biome =
                            mc.level.registryAccess().registryOrThrow(Registries.BIOME).get(rl);
                    if (biome != null) {
                        color = biome.getGrassColor(0.0, 0.0) & 0xFFFFFF;
                    }
                }
                int cx = x + (i % cols) * 11;
                int cy = y + 14 + (i / cols) * 11;
                g.fill(cx, cy, cx + 9, cy + 9, 0xFF000000);
                g.fill(cx + 1, cy + 1, cx + 8, cy + 8, 0xFF000000 | color);
            }
            if (array.size() > cols * 6) {
                g.drawString(font, "... and " + (array.size() - cols * 6) + " more", x, y + 82, 0x888888);
            }
        } catch (Exception e) {
            drawText(g, font, text, x, y, w, 80);
        }
    }

    private static void drawStation(GuiGraphics g, Font font, String text, int x, int y, int w) {
        try {
            JsonObject e = JsonParser.parseString(text).getAsJsonObject();
            boolean block = e.has("mode") && e.get("mode").getAsString().equals("BLOCK");
            String station = e.has("station") ? e.get("station").getAsString() : "anvil";
            ItemStack left = stackOf(e.has("left") ? e.get("left").getAsString() : null, 1);
            ItemStack right = stackOf(e.has("right") ? e.get("right").getAsString() : null, 1);
            Ui.slot(g, x, y, 18);
            if (!left.isEmpty()) {
                g.renderItem(left, x + 1, y + 1);
            }
            g.drawString(font, "+", x + 22, y + 5, 0xFFFFFF);
            Ui.slot(g, x + 32, y, 18);
            if (!right.isEmpty()) {
                g.renderItem(right, x + 33, y + 1);
            }
            if (block) {
                g.drawString(font, "BLOCKED", x + 58, y + 5, 0xFF5555);
            } else {
                g.drawString(font, "->", x + 56, y + 5, 0xFFFFFF);
                ItemStack result = stackOf(e.has("result") ? e.get("result").getAsString() : null,
                        e.has("count") ? e.get("count").getAsInt() : 1);
                Ui.slot(g, x + 70, y, 18);
                if (!result.isEmpty()) {
                    g.renderItem(result, x + 71, y + 1);
                    g.renderItemDecorations(font, result, x + 71, y + 1);
                }
                String extra = station.equals("anvil")
                        ? "Costs " + (e.has("cost") ? e.get("cost").getAsInt() : 1) + " levels"
                        : "Gives " + (e.has("xp") ? e.get("xp").getAsInt() : 0) + " XP";
                g.drawString(font, extra, x, y + 26, 0xAAAAAA);
            }
            g.drawString(font, station.equals("anvil") ? "Anvil" : "Grindstone", x, y + 40, 0xFFFF55);
        } catch (Exception ex) {
            drawText(g, font, text, x, y, w, 60);
        }
    }

    private static void drawTag(GuiGraphics g, Font font, String text, int x, int y, int w) {
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            String registry = o.has("registry") ? o.get("registry").getAsString() : "item";
            JsonArray members = o.getAsJsonArray("members");
            g.drawString(font, members.size() + " in the tag", x, y, 0xFFFFFF);
            int cols = Math.max(1, w / 18);
            for (int i = 0; i < members.size() && i < cols * 4; i++) {
                ItemStack icon = tagIcon(registry, members.get(i).getAsString());
                int cx = x + (i % cols) * 18;
                int cy = y + 12 + (i / cols) * 18;
                Ui.slot(g, cx, cy, 18);
                if (!icon.isEmpty()) {
                    g.renderItem(icon, cx + 1, cy + 1);
                }
            }
            if (members.size() > cols * 4) {
                g.drawString(font, "... and " + (members.size() - cols * 4) + " more", x, y + 88, 0x888888);
            }
        } catch (Exception e) {
            drawText(g, font, text, x, y, w, 60);
        }
    }

    private static ItemStack tagIcon(String registry, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        if (registry.equals("block")) {
            Block block = BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
            return block == null || block.asItem() == Items.AIR ? ItemStack.EMPTY : new ItemStack(block.asItem());
        }
        if (registry.equals("entity_type")) {
            net.minecraft.world.entity.EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
            net.minecraft.world.item.SpawnEggItem egg = type == null ? null : net.minecraft.world.item.SpawnEggItem.byId(type);
            return egg == null ? new ItemStack(Items.BONE) : new ItemStack(egg);
        }
        return BuiltInRegistries.ITEM.getOptional(rl).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    /** A flat JSON object shown as "name: value" lines. */
    private static void drawFlat(GuiGraphics g, Font font, String text, int x, int y, int w) {
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            int line = 0;
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                String value = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString();
                g.drawString(font, font.plainSubstrByWidth(e.getKey().replace('_', ' ') + ": " + value, w),
                        x, y + line * 11, line == 0 ? 0xFFFFFF : 0xCCCCCC);
                line++;
            }
        } catch (Exception ex) {
            drawText(g, font, text, x, y, w, 80);
        }
    }

    private static void drawText(GuiGraphics g, Font font, String text, int x, int y, int w, int h) {
        String flat = text.replace('\n', ' ');
        int lines = Math.max(1, h / 10);
        int pos = 0;
        for (int i = 0; i < lines && pos < flat.length(); i++) {
            String part = font.plainSubstrByWidth(flat.substring(pos), w);
            if (part.isEmpty()) {
                break;
            }
            g.drawString(font, part, x, y + i * 10, 0xAAAAAA);
            pos += part.length();
        }
    }
}
