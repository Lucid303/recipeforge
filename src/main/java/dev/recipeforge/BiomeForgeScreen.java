package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/**
 * Biome Forge: pick a biome, pick a colour slot, drag on the colour wheel.
 * The block preview on the left shows grass, leaves, water and tall grass in the chosen colours.
 */
public class BiomeForgeScreen extends ForgeScreen {
    private static final String[] SLOTS = {"grass", "foliage", "water", "water_fog", "sky", "fog"};
    private static final String[] LABELS = {"Grass", "Leaves", "Water", "Underwater", "Sky", "Fog"};
    private static final int WHEEL_R = 36;

    private final List<ResourceLocation> biomes = new ArrayList<>();
    private final List<ResourceLocation> filtered = new ArrayList<>();
    private EditBox searchBox;
    private EditBox hexBox;
    private String lastSearch = "";
    private ResourceLocation selected = null;
    private final int[] working = new int[6];
    private final int[] original = new int[6];
    private int slot = 0;
    private float hue = 0f;
    private float sat = 0f;
    private float val = 1f;
    private boolean suppressHex = false;
    private int dragging = 0;
    private int page = 0;
    private int perPage = 1;
    private int seenVersion = -1;

    // layout
    private final int col0 = 8;
    private int col1;
    private int wheelX;
    private int wheelY;

    private ResourceLocation preselect = null;

    public BiomeForgeScreen(Screen parent) {
        super(Component.literal("Biome Forge"), parent);
    }

    public BiomeForgeScreen(Screen parent, ResourceLocation preselect) {
        super(Component.literal("Biome Forge"), parent);
        this.preselect = preselect;
    }

    // ------------------------------------------------------------ data

    private void loadBiomes() {
        biomes.clear();
        seenVersion = BiomeClient.version;
        if (this.minecraft != null && this.minecraft.level != null) {
            biomes.addAll(this.minecraft.level.registryAccess().registryOrThrow(Registries.BIOME).keySet());
            biomes.sort((a, b) -> a.toString().compareTo(b.toString()));
        }
    }

    private void selectBiome(ResourceLocation id) {
        selected = id;
        if (id == null || this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        Biome biome = this.minecraft.level.registryAccess().registryOrThrow(Registries.BIOME).get(id);
        if (biome == null) {
            return;
        }
        for (int i = 0; i < 6; i++) {
            working[i] = BiomeServer.effective(biome, SLOTS[i]);
            original[i] = working[i];
        }
        loadSlot();
    }

    private void loadSlot() {
        int c = working[slot];
        float r = ((c >> 16) & 255) / 255f;
        float g = ((c >> 8) & 255) / 255f;
        float b = (c & 255) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        val = max;
        sat = max == 0 ? 0 : d / max;
        float h;
        if (d == 0) {
            h = 0;
        } else if (max == r) {
            h = ((g - b) / d) % 6f;
        } else if (max == g) {
            h = (b - r) / d + 2f;
        } else {
            h = (r - g) / d + 4f;
        }
        hue = (h / 6f + 1f) % 1f;
        updateHex();
    }

    private void updateHex() {
        if (hexBox != null) {
            suppressHex = true;
            hexBox.setValue(String.format(Locale.ROOT, "#%06X", working[slot] & 0xFFFFFF));
            suppressHex = false;
        }
    }

    private void applyWheel() {
        working[slot] = Mth.hsvToRgb(hue, sat, val) & 0xFFFFFF;
        updateHex();
    }

    // ------------------------------------------------------------ layout

    @Override
    protected void init() {
        String prev = searchBox != null ? searchBox.getValue() : lastSearch;
        loadBiomes();
        col1 = col0 + 140;
        wheelX = col1 + 112 + 16 + WHEEL_R + 2;
        wheelY = 76;
        perPage = Math.max(1, (this.height - 48 - 36) / 15);

        forgetDynamic();
        searchBox = new EditBox(this.font, col0, 24, 132, 18, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search (@minecraft)"));
        searchBox.setValue(prev);
        searchBox.setResponder(t -> {
            page = 0;
            applyFilter(t);
            buildList();
        });
        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);

        applyFilter(prev);
        if (preselect != null) {
            ResourceLocation wanted = preselect;
            preselect = null;
            if (biomes.contains(wanted)) {
                selectBiome(wanted);
                int at = filtered.indexOf(wanted);
                if (at >= 0) {
                    page = at / perPage;
                }
            }
        }
        buildList();
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(col0, this.height - 28, 24, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(col0 + 108, this.height - 28, 24, 20).build());

        for (int i = 0; i < 6; i++) {
            final int which = i;
            this.addRenderableWidget(Button.builder(Component.literal((slot == i ? "> " : "") + LABELS[i]), b -> {
                slot = which;
                loadSlot();
                this.rebuildWidgets();
            }).bounds(col1, 26 + i * 20, 96, 18).build());
        }

        hexBox = new EditBox(this.font, wheelX - 38, wheelY + WHEEL_R + 24, 76, 16, Component.literal("Hex"));
        hexBox.setMaxLength(7);
        hexBox.setResponder(this::onHex);
        this.addRenderableWidget(hexBox);
        this.addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply())
                .bounds(wheelX - 38, wheelY + WHEEL_R + 44, 76, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Reset biome"), b -> {
            if (selected != null) {
                PacketDistributor.sendToServer(new RecipeEditPayload("BIOME_RESET", selected.toString(), ""));
                status = "Resetting...";
            }
        }).bounds(wheelX - 38, wheelY + WHEEL_R + 64, 76, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width - 78, this.height - 28, 70, 20).build());
        updateHex();
    }

    private void applyFilter(String text) {
        lastSearch = text == null ? "" : text;
        List<String> namespaces = new ArrayList<>();
        for (ResourceLocation id : biomes) {
            if (!namespaces.contains(id.getNamespace())) {
                namespaces.add(id.getNamespace());
            }
        }
        SearchFilter f = SearchFilter.parse(lastSearch, namespaces);
        filtered.clear();
        for (ResourceLocation id : biomes) {
            if (f.matches(id, id.getPath().replace('_', ' '))) {
                filtered.add(id);
            }
        }
    }

    @Override
    protected void scrollPage(int delta) {
        changePage(delta);
    }

    private void buildList() {
        clearDynamic();
        page = Math.max(0, Math.min(page, (filtered.size() - 1) / perPage));
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= filtered.size()) {
                break;
            }
            final ResourceLocation id = filtered.get(index);
            String name = id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
            String label = (id.equals(selected) ? "> " : "") + name;
            addDynamic(Button.builder(Component.literal(this.font.plainSubstrByWidth(label, 124)),
                    b -> {
                        selectBiome(id);
                        buildList();
                    }).bounds(col0, 46 + i * 15, 132, 14).build());
        }
    }

    private void changePage(int delta) {
        int max = Math.max(0, (filtered.size() - 1) / perPage);
        int newPage = Math.max(0, Math.min(max, page + delta));
        if (newPage != page) {
            page = newPage;
            buildList();
        }
    }

    private void onHex(String text) {
        if (suppressHex) {
            return;
        }
        String t = text.startsWith("#") ? text.substring(1) : text;
        if (t.length() == 6) {
            try {
                working[slot] = Integer.parseInt(t, 16) & 0xFFFFFF;
                int keep = working[slot];
                loadSlot();
                working[slot] = keep;
            } catch (NumberFormatException ignored) {
                // wait for a valid colour
            }
        }
    }

    private void apply() {
        if (selected == null) {
            status = "Pick a biome on the left first.";
            return;
        }
        int sent = 0;
        for (int i = 0; i < 6; i++) {
            if (working[i] != original[i]) {
                JsonObject o = new JsonObject();
                o.addProperty("slot", SLOTS[i]);
                o.addProperty("color", working[i]);
                PacketDistributor.sendToServer(new RecipeEditPayload("BIOME_SET", selected.toString(), o.toString()));
                sent++;
            }
        }
        status = sent == 0 ? "Nothing changed yet. Drag on the colour wheel first." : "Applying...";
        if (sent > 0 && BiomeClient.lastError != null) {
            status = "Colours could not be applied here: " + BiomeClient.lastError;
        }
    }

    @Override
    protected void onForgeTick() {
        if (seenVersion != BiomeClient.version) {
            ResourceLocation keep = selected;
            this.rebuildWidgets();
            selectBiome(keep);
        }
    }

    // ------------------------------------------------------------ mouse (colour wheel)

    private boolean wheelInput(double mx, double my, boolean start) {
        double dx = mx - wheelX;
        double dy = my - wheelY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (start && dragging == 0) {
            if (dist <= WHEEL_R) {
                dragging = 1;
            } else if (Ui.inside(mx, my, wheelX - WHEEL_R, wheelY + WHEEL_R + 8, WHEEL_R * 2, 8)) {
                dragging = 2;
            }
        }
        if (dragging == 1) {
            float h = (float) (Math.atan2(dy, dx) / (2 * Math.PI));
            hue = h < 0 ? h + 1f : h;
            sat = (float) Math.min(1.0, dist / WHEEL_R);
            applyWheel();
            return true;
        }
        if (dragging == 2) {
            val = (float) Math.max(0.0, Math.min(1.0, (mx - (wheelX - WHEEL_R)) / (WHEEL_R * 2)));
            applyWheel();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0) {
            dragging = 0;
            return wheelInput(mouseX, mouseY, true);
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging != 0 && button == 0) {
            return wheelInput(mouseX, mouseY, false);
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = 0;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ------------------------------------------------------------ drawing

    private static TextureAtlasSprite sprite(String path) {
        return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(ResourceLocation.withDefaultNamespace(path));
    }

    /** Draws a texture squashed onto a parallelogram. (ax,ay) is the image of the x axis, (bx,by) of the y axis. */
    private static void face(GuiGraphics g, TextureAtlasSprite sp, float ox, float oy, float ax, float ay,
                             float bx, float by, int rgb, float shade, float alpha) {
        float r = ((rgb >> 16) & 255) / 255f * shade;
        float gr = ((rgb >> 8) & 255) / 255f * shade;
        float b = (rgb & 255) / 255f * shade;
        g.pose().pushPose();
        g.pose().mulPose(new Matrix4f(ax / 16f, ay / 16f, 0, 0, bx / 16f, by / 16f, 0, 0, 0, 0, 1, 0, ox, oy, 0, 1));
        g.setColor(r, gr, b, alpha);
        g.blit(0, 0, 0, 16, 16, sp);
        g.setColor(1f, 1f, 1f, 1f);
        g.pose().popPose();
    }

    private static void top(GuiGraphics g, TextureAtlasSprite sp, float cx, float cy, float s, int rgb, float alpha) {
        face(g, sp, cx, cy, s, s / 2, -s, s / 2, rgb, 1.0f, alpha);
    }

    private static void left(GuiGraphics g, TextureAtlasSprite sp, float cx, float cy, float s, int rgb, float alpha) {
        face(g, sp, cx - s, cy + s / 2, s, s / 2, 0, s, rgb, 0.8f, alpha);
    }

    private static void right(GuiGraphics g, TextureAtlasSprite sp, float cx, float cy, float s, int rgb, float alpha) {
        face(g, sp, cx, cy + s, s, -s / 2, 0, s, rgb, 0.6f, alpha);
    }

    private void drawPreview(GuiGraphics g, int x, int y) {
        int grass = working[0];
        int leaves = working[1];
        int water = working[2];
        float s = 12f;

        // grass block
        TextureAtlasSprite gTop = sprite("block/grass_block_top");
        TextureAtlasSprite gSide = sprite("block/grass_block_side");
        TextureAtlasSprite gOver = sprite("block/grass_block_side_overlay");
        float c1 = x + 14;
        left(g, gSide, c1, y, s, 0xFFFFFF, 1f);
        left(g, gOver, c1, y, s, grass, 1f);
        right(g, gSide, c1, y, s, 0xFFFFFF, 1f);
        right(g, gOver, c1, y, s, grass, 1f);
        top(g, gTop, c1, y, s, grass, 1f);

        // leaves
        TextureAtlasSprite leaf = sprite("block/oak_leaves");
        float c2 = x + 42;
        left(g, leaf, c2, y, s, leaves, 1f);
        right(g, leaf, c2, y, s, leaves, 1f);
        top(g, leaf, c2, y, s, leaves, 1f);

        // water
        TextureAtlasSprite wat = sprite("block/water_still");
        float c3 = x + 70;
        left(g, wat, c3, y, s, water, 0.85f);
        right(g, wat, c3, y, s, water, 0.85f);
        top(g, wat, c3, y, s, water, 0.85f);

        // tall grass (two flat sprites on top of each other)
        TextureAtlasSprite tgTop = sprite("block/tall_grass_top");
        TextureAtlasSprite tgBottom = sprite("block/tall_grass_bottom");
        face(g, tgTop, x + 88, y, 20, 0, 0, 20, grass, 1f, 1f);
        face(g, tgBottom, x + 88, y + 20, 20, 0, 0, 20, grass, 1f, 1f);

        g.drawCenteredString(this.font, "Grass", (int) c1, y + 28, 0xAAAAAA);
        g.drawCenteredString(this.font, "Leaf", (int) c2, y + 28, 0xAAAAAA);
        g.drawCenteredString(this.font, "Water", (int) c3, y + 28, 0xAAAAAA);
        g.drawCenteredString(this.font, "Tall", x + 98, y + 44, 0xAAAAAA);

        // sky and fog strip
        g.fillGradient(x, y + 58, x + 112, y + 78, 0xFF000000 | working[4], 0xFF000000 | working[5]);
        g.drawString(this.font, "Sky / fog", x + 2, y + 82, 0xAAAAAA);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, StationInfo.biome(),
                selected == null ? "Pick a biome" : selected.toString());

        // colour swatches next to the slot buttons
        for (int i = 0; i < 6; i++) {
            int x = col1 + 100;
            int y = 26 + i * 20;
            g.fill(x, y, x + 14, y + 18, 0xFF000000);
            g.fill(x + 1, y + 1, x + 13, y + 17, 0xFF000000 | working[i]);
        }

        // colour wheel
        for (int dy = -WHEEL_R; dy < WHEEL_R; dy += 2) {
            for (int dx = -WHEEL_R; dx < WHEEL_R; dx += 2) {
                double dist = Math.sqrt((dx + 1.0) * (dx + 1.0) + (dy + 1.0) * (dy + 1.0));
                if (dist > WHEEL_R) {
                    continue;
                }
                float h = (float) (Math.atan2(dy + 1.0, dx + 1.0) / (2 * Math.PI));
                if (h < 0) {
                    h += 1f;
                }
                g.fill(wheelX + dx, wheelY + dy, wheelX + dx + 2, wheelY + dy + 2,
                        0xFF000000 | Mth.hsvToRgb(h, (float) (dist / WHEEL_R), val));
            }
        }
        double angle = hue * 2 * Math.PI;
        int mx = wheelX + (int) (Math.cos(angle) * sat * WHEEL_R);
        int my = wheelY + (int) (Math.sin(angle) * sat * WHEEL_R);
        Ui.outline(g, mx - 3, my - 3, 7, 7, 0xFFFFFFFF);
        Ui.outline(g, mx - 2, my - 2, 5, 5, 0xFF000000);

        // brightness slider
        int sy = wheelY + WHEEL_R + 8;
        for (int i = 0; i < WHEEL_R * 2; i++) {
            g.fill(wheelX - WHEEL_R + i, sy, wheelX - WHEEL_R + i + 1, sy + 8,
                    0xFF000000 | Mth.hsvToRgb(hue, sat, i / (float) (WHEEL_R * 2)));
        }
        int vx = wheelX - WHEEL_R + (int) (val * WHEEL_R * 2);
        Ui.outline(g, vx - 1, sy - 1, 3, 10, 0xFFFFFFFF);

        drawPreview(g, col1, 150);
        if (BiomeClient.lastError != null && status.isEmpty()) {
            status = "Biome colours are not working: " + BiomeClient.lastError;
        }
        g.drawString(this.font, "Page " + (page + 1) + " / " + (Math.max(0, (filtered.size() - 1) / perPage) + 1),
                col0 + 34, this.height - 22, 0xAAAAAA);
        drawStatus(g, col1, this.height - 40, this.width - col1 - 8);
    }
}
