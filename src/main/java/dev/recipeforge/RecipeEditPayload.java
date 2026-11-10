package dev.recipeforge;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server request.
 * action: SAVE, DISABLE, RESTORE, REAPPLY (json = history number), GET_RECIPE, GET_HISTORY.
 */
public record RecipeEditPayload(String action, String recipeId, String json) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RecipeEditPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(RecipeForge.MODID, "edit"));

    public static final StreamCodec<ByteBuf, RecipeEditPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), RecipeEditPayload::action,
            ByteBufCodecs.stringUtf8(256), RecipeEditPayload::recipeId,
            ByteBufCodecs.stringUtf8(30000), RecipeEditPayload::json,
            RecipeEditPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
