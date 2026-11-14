package dev.recipeforge.extras;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.recipeforge.ClientState;
import dev.recipeforge.ForgeScreen;
import dev.recipeforge.ItemPickerScreen;
import dev.recipeforge.RecipeEditPayload;
import dev.recipeforge.StationInfo;
import dev.recipeforge.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;

/** The members of one tag, shown as icons. Click a member to take it out, press Add to put something in. */
public class TagScreen extends ForgeScreen {
    private static final int CELL = 20;

    private final String registry;
    private final String tagId;
    private final List<String> current = new ArrayList<>();
    private final Set<String> added = new LinkedHashSet<>();
    private final Set<String> removed = new LinkedHashSet<>();
    private final List<String> shown = new ArrayList<>();
    private int page = 0;
    private int cols = 1;
    private int rows = 1;
    private int gx;
    private final int gy = 52;
    private int seenVersion = -1;

    public TagScreen(Screen parent, String registry, String tagId) {
        super(Component.literal("Tag"), parent);
        this.registry = registry;
        this.tagId = tagId;
    }

    private static <T> List<String> membersOf(Registry<T> reg, ResourceLocation tag) {
        List<String> out = new ArrayList<>();
        reg.getTag(TagKey.create(reg.key(), tag)).ifPresent(named -> named.forEach(holder ->
                holder.unwrapKey().ifPresent(key -> out.add(key.location().toString()))));
        out.sort(String::compareTo);
        return out;
    }

    private void loadMembers() {
        current.clear();
        ResourceLocation tag = ResourceLocation.tryParse(tagId);
        if (tag != null) {
            switch (registry) {
                case "block" -> current.addAll(membersOf(BuiltInRegistries.BLOCK, tag));
                case "entity_type" -> current.addAll(membersOf(BuiltInRegistries.ENTITY_TYPE, tag));
                default -> current.addAll(membersOf(BuiltInRegistries.ITEM, tag));
            }
        }
        seenVersion = ClientState.recipeVersion;
        shown.clear();
        shown.addAll(current);
        for (String a : added) {
            if (!shown.contains(a)) {
                shown.add(a);
            }
        }
    }

    static ItemStack iconOf(String registry, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        if (registry.equals("block")) {
            Block block = BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
            return block == null || block.asItem() == Items.AIR ? ItemStack.EMPTY : new ItemStack(block.asItem());
        }
        if (registry.equals("entity_type")) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
            SpawnEggItem egg = type == null ? null : SpawnEggItem.byId(type);
            return egg == null ? new ItemStack(Items.BONE) : new ItemStack(egg);
        }
        return BuiltInRegistries.ITEM.getOptional(rl).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    @Override
    protected void init() {
        loadMembers();
        cols = Math.max(1, Math.min(22, (this.width - 20) / CELL));
        rows = Math.max(1, (this.height - gy - 40) / CELL);
        gx = (this.width - cols * CELL) / 2;

        int by = this.height - 28;
        this.addRenderableWidget(Button.builder(Component.literal("Add"), b -> addMember())
                .bounds(gx, by, 56, 20).tooltip(Tooltip.create(Component.literal("Put another one into this tag."))).build());
        this.addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply())
                .bounds(gx + 60, by, 56, 20).tooltip(Tooltip.create(Component.literal("Save your changes to the game."))).build());
        this.addRenderableWidget(Button.builder(Component.literal("Restore"), b -> {
            PacketDistributor.sendToServer(new RecipeEditPayload("TAG_RESTORE", registry + "|" + tagId, ""));
            added.clear();
            removed.clear();
        }).bounds(gx + 120, by, 56, 20).tooltip(Tooltip.create(Component.literal("Put the original tag back."))).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width - 78, by, 70, 20).build());
        addPager(this.width / 2 + 40, by, () -> changePage(-1), () -> changePage(1));
    }

    private void addMember() {
        if (registry.equals("entity_type")) {
            List<String> ids = new ArrayList<>();
            BuiltInRegistries.ENTITY_TYPE.keySet().forEach(k -> ids.add(k.toString()));
            ids.sort(String::compareTo);
            this.minecraft.setScreen(new IdPickerScreen(this, "Add a mob", ids, id -> {
                added.add(id);
                removed.remove(id);
            }));
        } else {
            this.minecraft.setScreen(new ItemPickerScreen(this, "Add to the tag", registry.equals("block"), id -> {
                added.add(id);
                removed.remove(id);
            }));
        }
    }

    private void apply() {
        JsonArray add = new JsonArray();
        added.stream().filter(a -> !current.contains(a)).forEach(add::add);
        JsonArray remove = new JsonArray();
        removed.stream().filter(current::contains).forEach(remove::add);
        if (add.isEmpty() && remove.isEmpty()) {
            status = "Nothing changed yet.";
            return;
        }
        JsonObject o = new JsonObject();
        o.add("add", add);
        o.add("remove", remove);
        PacketDistributor.sendToServer(new RecipeEditPayload("TAG_EDIT", registry + "|" + tagId, o.toString()));
        status = "Saving...";
    }

    private int perPage() {
        return Math.max(1, cols * rows);
    }

    private int maxPage() {
        return Math.max(0, (shown.size() - 1) / perPage());
    }

    private void changePage(int delta) {
        page = Math.max(0, Math.min(maxPage(), page + delta));
    }

    @Override
    protected void scrollPage(int delta) {
        changePage(delta);
    }

    @Override
    protected void onForgeTick() {
        if (seenVersion != ClientState.recipeVersion) {
            loadMembers();
        }
    }

    @Override
    public void onServerResult(boolean ok, String message) {
        status = message;
        if (ok) {
            added.clear();
            removed.clear();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int start = page * perPage();
        for (int i = 0; i < perPage() && start + i < shown.size(); i++) {
            int x = gx + (i % cols) * CELL;
            int y = gy + (i / cols) * CELL;
            if (Ui.inside(mouseX, mouseY, x, y, CELL - 2, CELL - 2)) {
                String id = shown.get(start + i);
                if (added.contains(id)) {
                    added.remove(id);
                    loadMembers();
                } else if (!removed.remove(id)) {
                    removed.add(id);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, TagForgeScreen.INFO, "#" + tagId);
        g.drawString(this.font, "Click an icon to take it out of the tag (red). Green = newly added.", gx, 28, 0x888888);
        g.drawString(this.font, shown.size() + " in this tag", gx, 40, 0xAAAAAA);

        int start = page * perPage();
        Component hover = null;
        for (int i = 0; i < perPage() && start + i < shown.size(); i++) {
            String id = shown.get(start + i);
            int x = gx + (i % cols) * CELL;
            int y = gy + (i / cols) * CELL;
            Ui.slot(g, x, y, CELL - 2);
            ItemStack icon = iconOf(registry, id);
            if (!icon.isEmpty()) {
                g.renderItem(icon, x + 1, y + 1);
            }
            if (removed.contains(id)) {
                g.fill(x + 1, y + 1, x + CELL - 3, y + CELL - 3, 0x80FF0000);
            } else if (added.contains(id)) {
                Ui.outline(g, x, y, CELL - 2, CELL - 2, 0xFF55FF55);
            }
            if (Ui.inside(mouseX, mouseY, x, y, CELL - 2, CELL - 2)) {
                hover = Component.literal(id);
            }
        }
        drawPager(g, this.width / 2 + 40, this.height - 28, page, maxPage() + 1);
        drawStatus(g, gx, this.height - 52, this.width - 2 * gx);
        if (hover != null) {
            g.renderTooltip(this.font, hover, mouseX, mouseY);
        }
    }
}
