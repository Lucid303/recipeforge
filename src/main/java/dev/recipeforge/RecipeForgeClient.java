package dev.recipeforge;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Client-only entry point: the keybind that opens the editor. */
@Mod(value = RecipeForge.MODID, dist = Dist.CLIENT)
public class RecipeForgeClient {

    public static final KeyMapping OPEN_KEY = new KeyMapping(
            "key.recipeforge.open",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.recipeforge");

    public RecipeForgeClient(IEventBus modBus) {
        modBus.addListener(this::registerKeys);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onRecipesUpdated);
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_KEY);
    }

    private void onRecipesUpdated(RecipesUpdatedEvent event) {
        ClientState.recipeVersion++;
    }

    private void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_KEY.consumeClick()) {
            if (mc.player == null) {
                continue;
            }
            if (!mc.player.isCreative()) {
                mc.player.displayClientMessage(Component.literal("Recipe Forge only works in creative mode."), true);
                continue;
            }
            mc.setScreen(new ItemBrowserScreen(null));
        }
    }
}
