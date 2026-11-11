package dev.recipeforge;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client. kind is OK, FAIL, RECIPE, RECIPE_ERROR or HISTORY. */
public record ServerMessagePayload(String kind, String id, String data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServerMessagePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(WorldForge.MODID, "msg"));

    public static final StreamCodec<ByteBuf, ServerMessagePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), ServerMessagePayload::kind,
            ByteBufCodecs.stringUtf8(256), ServerMessagePayload::id,
            ByteBufCodecs.stringUtf8(900000), ServerMessagePayload::data,
            ServerMessagePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
