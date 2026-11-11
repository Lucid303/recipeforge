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
                case "minecraft:crafting_shaped" -> {
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
                case "minecraft:crafting_shapeless" -> {
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
            case "structure" -> drawStructure(g, font, action, text, x, y, w);
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

    private static void drawStructure(GuiGraphics g, Font font, String action, String text, int x, int y, int w) {
        if (action.equals("TEMPLATE_REPLACE")) {
            ResourceLocation rl = ResourceLocation.tryParse(text);
            Block block = rl == null ? null : BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
            ItemStack icon = block == null || block.asItem() == Items.AIR ? ItemStack.EMPTY : new ItemStack(block.asItem());
            Ui.slot(g, x, y, 18);
            if (!icon.isEmpty()) {
                g.renderItem(icon, x + 1, y + 1);
            }
            g.drawString(font, font.plainSubstrByWidth(text, w - 24), x + 22, y + 5, 0xFFFFFF);
            return;
        }
        try {
            JsonArray array = JsonParser.parseString(text).getAsJsonArray();
            g.drawString(font, array.size() + " biomes:", x, y, 0xFFFFFF);
            for (int i = 0; i < array.size() && i < 6; i++) {
                g.drawString(font, font.plainSubstrByWidth(array.get(i).getAsString(), w), x, y + 12 + i * 10, 0xAAAAAA);
            }
            if (array.size() > 6) {
                g.drawString(font, "... and " + (array.size() - 6) + " more", x, y + 72, 0x888888);
            }
        } catch (Exception e) {
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
