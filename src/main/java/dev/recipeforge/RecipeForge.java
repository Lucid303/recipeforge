package dev.recipeforge;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/** Common (client + server) entry point. */
@Mod(RecipeForge.MODID)
public class RecipeForge {
    public static final String MODID = "recipeforge";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RecipeForge(IEventBus modBus) {
        modBus.addListener(PackSetup::addPacks);
        modBus.addListener(Network::register);
    }
}
