package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.neoforged.neoforge.network.PacketDistributor;

/** Loom editor: for every banner pattern, choose which pattern item it needs (or none). */
public class LoomScreen extends ForgeScreen {
    private static final int ROW = 24;
    private static final List<String> SOURCES = LoomServer.SOURCES;

    private record Row(ResourceLocation id, String current) {}

    private final List<Row> rows = new ArrayList<>();
    private final List<Row> filtered = new ArrayList<>();
    private EditBox searchBox;
    private String lastFilter = "";
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private int top = 52;
    private int seenVersion = -1;

    public LoomScreen(Screen parent) {
        super(Component.literal("Loom"), parent);
    }

    private static TagKey<BannerPattern> tagFor(String source) {
        return TagKey.create(Registries.BANNER_PATTERN, ResourceLocation.withDefaultNamespace(LoomServer.tagPath(source)));
    }

    private static Item iconFor(String source) {
        return switch (source) {
            case "flower" -> Items.FLOWER_BANNER_PATTERN;
            case "creeper" -> Items.CREEPER_BANNER_PATTERN;
            case "skull" -> Items.SKULL_BANNER_PATTERN;
            case "mojang" -> Items.MOJANG_BANNER_PATTERN;
            case "globe" -> Items.GLOBE_BANNER_PATTERN;
            case "piglin" -> Items.PIGLIN_BANNER_PATTERN;
            case "flow" -> Items.FLOW_BANNER_PATTERN;
            case "guster" -> Items.GUSTER_BANNER_PATTERN;
            default -> Items.WHITE_BANNER;
        };
    }

    private static String nameFor(String source) {
        if (source == null) {
            return "Not in the loom";
        }
        return switch (source) {
            case "none" -> "No item needed";
            case "flower" -> "Flower Charge item";
            case "creeper" -> "Creeper Charge item";
            case "skull" -> "Skull Charge item";
            case "mojang" -> "Thing item";
            case "globe" -> "Globe item";
            case "piglin" -> "Snout item";
            case "flow" -> "Flow item";
            case "guster" -> "Guster item";
            default -> source;
        };
    }

    private void loadRows() {
        rows.clear();
        seenVersion = ClientState.recipeVersion;
        if (this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        try {
            Registry<BannerPattern> registry = this.minecraft.level.registryAccess().registryOrThrow(Registries.BANNER_PATTERN);
            registry.holders().forEach(holder -> {
                String current = null;
                for (String s : SOURCES) {
                    if (holder.is(tagFor(s))) {
                        current = s;
                        break;
                    }
                }
                rows.add(new Row(holder.key().location(), current));
            });
            rows.sort((a, b) -> a.id().toString().compareTo(b.id().toString()));
        } catch (Exception e) {
            status = "Could not read the banner patterns: " + e.getMessage();
        }
    }

    @Override
    protected void init() {
        String prev = searchBox != null ? searchBox.getValue() : lastFilter;
        loadRows();
        boxWidth = Math.min(this.width - 20, 440);
        left = (this.width - boxWidth) / 2;
        perPage = Math.max(1, (this.height - top - 40) / ROW);

        searchBox = new EditBox(this.font, left, 26, 200, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search patterns..."));
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
            final Row row = filtered.get(index);
            int y = top + i * ROW;
            this.addRenderableWidget(Button.builder(Component.literal("Needs: " + nameFor(row.current())), b -> {
                int next = (row.current() == null ? 0 : (SOURCES.indexOf(row.current()) + 1)) % SOURCES.size();
                PacketDistributor.sendToServer(new RecipeEditPayload("LOOM_SET", row.id().toString(), SOURCES.get(next)));
                status = "Changing " + row.id().getPath() + "...";
            }).bounds(left + boxWidth - 170, y, 170, 20).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Put the loom back to normal"), b -> {
            PacketDistributor.sendToServer(new RecipeEditPayload("LOOM_RESET", "", ""));
            status = "Resetting...";
        }).bounds(left, this.height - 28, 170, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(left + boxWidth - 70, this.height - 28, 70, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(left + 190, this.height - 28, 26, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(left + 280, this.height - 28, 26, 20).build());
    }

    private void applyFilter(String text) {
        lastFilter = text == null ? "" : text;
        String f = lastFilter.trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (Row r : rows) {
            if (f.isEmpty() || r.id().toString().contains(f)) {
                filtered.add(r);
            }
        }
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
    protected void onForgeTick() {
        if (seenVersion != ClientState.recipeVersion) {
            this.rebuildWidgets();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, StationInfo.loom(), "Which item each banner pattern needs");
        g.drawString(this.font, "Click a button to switch to the next item", left + 210, 32, 0xAAAAAA);

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= filtered.size()) {
                break;
            }
            Row row = filtered.get(index);
            int y = top + i * ROW;
            String name = row.id().getNamespace().equals("minecraft") ? row.id().getPath() : row.id().toString();
            g.drawString(this.font, name.replace('_', ' '), left + 4, y + 6, 0xFFFFFF);
            g.renderItem(new ItemStack(iconFor(row.current() == null ? "none" : row.current())),
                    left + boxWidth - 190, y + 2);
        }
        g.drawCenteredString(this.font, "Page " + (page + 1) + " / "
                + (Math.max(0, (filtered.size() - 1) / perPage) + 1), left + 243, this.height - 42, 0xAAAAAA);
        drawStatus(g, left, this.height - 54, boxWidth);
    }
}
