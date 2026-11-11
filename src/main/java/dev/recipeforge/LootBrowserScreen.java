package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;

/** Loot Forge: all loot tables (block drops, mob drops, chests ...) from vanilla and every mod. */
public class LootBrowserScreen extends ForgeScreen {
    private static final int ROW = 20;
    private static final String[] CATEGORIES = {"all", "blocks", "entities", "chests", "other"};
    private static final String[] CATEGORY_LABELS = {"All", "Blocks", "Mobs", "Chests", "Other"};

    private final List<String> all = new ArrayList<>();
    private final List<String> filtered = new ArrayList<>();
    private String category = "all";
    private EditBox searchBox;
    private String lastFilter = "";
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private final int top = 76;
    private boolean requested = false;

    public LootBrowserScreen(Screen parent) {
        super(Component.literal("Loot Forge"), parent);
        this.status = "Loading loot tables...";
    }

    @Override
    protected void init() {
        if (!requested) {
            requested = true;
            PacketDistributor.sendToServer(new RecipeEditPayload("LOOT_LIST", "", ""));
        }
        String prev = searchBox != null ? searchBox.getValue() : lastFilter;
        boxWidth = Math.min(this.width - 20, 460);
        left = (this.width - boxWidth) / 2;
        perPage = Math.max(1, (this.height - top - 40) / ROW);

        int bw = 64;
        for (int i = 0; i < CATEGORIES.length; i++) {
            final String cat = CATEGORIES[i];
            String label = cat.equals(category) ? "[" + CATEGORY_LABELS[i] + "]" : CATEGORY_LABELS[i];
            this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
                category = cat;
                page = 0;
                applyFilter(searchBox.getValue());
                this.rebuildWidgets();
            }).bounds(left + i * (bw + 4), 26, bw, 20).build());
        }

        searchBox = new EditBox(this.font, left, 50, 220, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search  (try @minecraft)"));
        searchBox.setValue(prev);
        searchBox.setResponder(t -> {
            page = 0;
            applyFilter(t);
            this.rebuildWidgets();
        });
        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);

        applyFilter(prev);
        page = Math.max(0, Math.min(page, (filtered.size() - 1) / perPage));
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= filtered.size()) {
                break;
            }
            final String id = filtered.get(index);
            this.addRenderableWidget(Button.builder(
                    Component.literal(this.font.plainSubstrByWidth(id, boxWidth - 40)),
                    b -> this.minecraft.setScreen(new LootTableScreen(this, id)))
                    .bounds(left + 22, top + i * ROW, boxWidth - 22, ROW - 2).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width - 78, this.height - 28, 70, 20).build());
        addPager(this.width / 2, this.height - 28, () -> changePage(-1), () -> changePage(1));
    }

    private static String categoryOf(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        if (path.startsWith("blocks/")) {
            return "blocks";
        }
        if (path.startsWith("entities/")) {
            return "entities";
        }
        if (path.startsWith("chests/")) {
            return "chests";
        }
        return "other";
    }

    private void applyFilter(String text) {
        lastFilter = text == null ? "" : text;
        List<String> namespaces = new ArrayList<>();
        for (String id : all) {
            String ns = id.substring(0, id.indexOf(':'));
            if (!namespaces.contains(ns)) {
                namespaces.add(ns);
            }
        }
        SearchFilter f = SearchFilter.parse(lastFilter, namespaces);
        filtered.clear();
        for (String id : all) {
            if (!category.equals("all") && !categoryOf(id).equals(category)) {
                continue;
            }
            if (f.matches(ResourceLocation.parse(id), id.substring(id.indexOf(':') + 1).replace('_', ' '))) {
                filtered.add(id);
            }
        }
    }

    @Override
    protected void scrollPage(int delta) {
        changePage(delta);
    }

    private void changePage(int delta) {
        int max = Math.max(0, (filtered.size() - 1) / perPage);
        int newPage = Math.max(0, Math.min(max, page + delta));
        if (newPage != page) {
            page = newPage;
            this.rebuildWidgets();
        }
    }

    @Override
    public void onData(String kind, String id, String data) {
        if (!kind.equals("LOOT_LIST")) {
            return;
        }
        all.clear();
        try {
            JsonArray array = JsonParser.parseString(data).getAsJsonArray();
            for (JsonElement e : array) {
                all.add(e.getAsString());
            }
            status = all.size() + " loot tables";
        } catch (Exception ex) {
            status = "Could not read the list: " + ex.getMessage();
        }
        this.rebuildWidgets();
    }

    private static ItemStack iconFor(String id) {
        try {
            String ns = id.substring(0, id.indexOf(':'));
            String path = id.substring(id.indexOf(':') + 1);
            if (path.startsWith("blocks/")) {
                Block block = BuiltInRegistries.BLOCK.getOptional(
                        ResourceLocation.fromNamespaceAndPath(ns, path.substring(7))).orElse(null);
                if (block != null && block.asItem() != Items.AIR) {
                    return new ItemStack(block.asItem());
                }
                return new ItemStack(Items.STONE);
            }
            if (path.startsWith("entities/")) {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(
                        ResourceLocation.fromNamespaceAndPath(ns, path.substring(9))).orElse(null);
                SpawnEggItem egg = type == null ? null : SpawnEggItem.byId(type);
                Item item = egg == null ? Items.BONE : egg;
                return new ItemStack(item);
            }
            if (path.startsWith("chests/")) {
                return new ItemStack(Items.CHEST);
            }
            if (path.startsWith("gameplay/")) {
                return new ItemStack(Items.FISHING_ROD);
            }
        } catch (Exception ignored) {
            // fall through to the paper icon
        }
        return new ItemStack(Items.PAPER);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, StationInfo.loot(), "Drops and chest loot");
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= filtered.size()) {
                break;
            }
            g.renderItem(iconFor(filtered.get(index)), left + 3, top + i * ROW);
        }
        drawPager(g, this.width / 2, this.height - 28, page, (filtered.size() - 1) / perPage + 1);
        drawStatus(g, left, this.height - 52, boxWidth);
    }
}
