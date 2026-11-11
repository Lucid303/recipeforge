package dev.recipeforge;

import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.AnvilUpdateEvent;
import net.neoforged.neoforge.event.GrindstoneEvent;

/** Makes the custom anvil and grindstone combinations actually work in game. */
public final class StationEvents {
    private StationEvents() {}

    private static boolean matches(ItemStack stack, String itemId) {
        if (stack.isEmpty() || itemId == null) {
            return false;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId);
    }

    private static Item itemOf(String id) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        if (rl == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    public static void onAnvil(AnvilUpdateEvent event) {
        List<StationData.Pair> list = StationData.snapshot("anvil");
        if (list.isEmpty()) {
            return;
        }
        ItemStack left = event.getLeft();
        ItemStack right = event.getRight();
        if (left.isEmpty() || right.isEmpty()) {
            return;
        }
        for (StationData.Pair p : list) {
            if (!matches(left, p.left) || !matches(right, p.right)) {
                continue;
            }
            if ("BLOCK".equals(p.mode)) {
                event.setCanceled(true);
                try {
                    if (event.getPlayer().containerMenu instanceof AnvilMenu menu) {
                        menu.getSlot(2).set(ItemStack.EMPTY);
                    }
                } catch (Exception ignored) {
                    // best effort: clears an old result that might still be showing
                }
                return;
            }
            Item out = itemOf(p.result);
            if (out == null) {
                continue;
            }
            int cost = Math.max(1, p.cost);
            event.setOutput(new ItemStack(out, Math.max(1, Math.min(64, p.count))));
            event.setCost(cost);
            event.setMaterialCost(1);
            return;
        }
    }

    public static void onGrindstone(GrindstoneEvent.OnPlaceItem event) {
        List<StationData.Pair> list = StationData.snapshot("grindstone");
        if (list.isEmpty()) {
            return;
        }
        ItemStack top = event.getTopItem();
        ItemStack bottom = event.getBottomItem();
        if (top.isEmpty() || bottom.isEmpty()) {
            return;
        }
        for (StationData.Pair p : list) {
            boolean straight = matches(top, p.left) && matches(bottom, p.right);
            boolean swapped = matches(top, p.right) && matches(bottom, p.left);
            if (!straight && !swapped) {
                continue;
            }
            if ("BLOCK".equals(p.mode)) {
                event.setCanceled(true);
                return;
            }
            Item out = itemOf(p.result);
            if (out == null) {
                continue;
            }
            int xp = Math.max(0, p.xp);
            event.setOutput(new ItemStack(out, Math.max(1, Math.min(64, p.count))));
            event.setXp(xp);
            return;
        }
    }
}
