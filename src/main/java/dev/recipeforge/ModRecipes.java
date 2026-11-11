package dev.recipeforge;

import java.util.List;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registers the "keep" crafting recipe types (worldforge:keep_shaped and worldforge:keep_shapeless). */
public final class ModRecipes {
    private ModRecipes() {}

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, WorldForge.MODID);

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<KeepRecipe>> KEEP_SHAPED =
            SERIALIZERS.register("keep_shaped", ModRecipes::shaped);

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<KeepRecipe>> KEEP_SHAPELESS =
            SERIALIZERS.register("keep_shapeless", ModRecipes::shapeless);

    private static RecipeSerializer<KeepRecipe> shaped() {
        final MapCodec<KeepRecipe> codec = RecordCodecBuilder.mapCodec(i -> i.group(
                RecipeSerializer.SHAPED_RECIPE.codec().forGetter(r -> (ShapedRecipe) r.delegate()),
                Ingredient.LIST_CODEC.optionalFieldOf("keep", List.of()).forGetter(KeepRecipe::keep)
        ).apply(i, (d, k) -> new KeepRecipe(d, k, true)));
        final StreamCodec<RegistryFriendlyByteBuf, KeepRecipe> stream = StreamCodec.composite(
                RecipeSerializer.SHAPED_RECIPE.streamCodec(), r -> (ShapedRecipe) r.delegate(),
                Ingredient.CONTENTS_STREAM_CODEC.apply(ByteBufCodecs.list()), KeepRecipe::keep,
                (d, k) -> new KeepRecipe(d, k, true));
        return new RecipeSerializer<>() {
            @Override
            public MapCodec<KeepRecipe> codec() {
                return codec;
            }

            @Override
            public StreamCodec<RegistryFriendlyByteBuf, KeepRecipe> streamCodec() {
                return stream;
            }
        };
    }

    private static RecipeSerializer<KeepRecipe> shapeless() {
        final MapCodec<KeepRecipe> codec = RecordCodecBuilder.mapCodec(i -> i.group(
                RecipeSerializer.SHAPELESS_RECIPE.codec().forGetter(r -> (ShapelessRecipe) r.delegate()),
                Ingredient.LIST_CODEC.optionalFieldOf("keep", List.of()).forGetter(KeepRecipe::keep)
        ).apply(i, (d, k) -> new KeepRecipe(d, k, false)));
        final StreamCodec<RegistryFriendlyByteBuf, KeepRecipe> stream = StreamCodec.composite(
                RecipeSerializer.SHAPELESS_RECIPE.streamCodec(), r -> (ShapelessRecipe) r.delegate(),
                Ingredient.CONTENTS_STREAM_CODEC.apply(ByteBufCodecs.list()), KeepRecipe::keep,
                (d, k) -> new KeepRecipe(d, k, false));
        return new RecipeSerializer<>() {
            @Override
            public MapCodec<KeepRecipe> codec() {
                return codec;
            }

            @Override
            public StreamCodec<RegistryFriendlyByteBuf, KeepRecipe> streamCodec() {
                return stream;
            }
        };
    }
}
