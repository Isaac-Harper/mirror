package io.monogram.mirror;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Common entry point: registers the Mirror block + item.
 *
 * The reflection is rendered entirely client-side and natively (no Immersive Portals) -
 * see {@code io.monogram.mirror.client.MirrorRenderer}. The block just marks a reflective
 * surface; it needs no block entity.
 */
public class MirrorMod implements ModInitializer {
    public static final String MOD_ID = "mirror";

    public static final Identifier MIRROR_ID =
        Identifier.fromNamespaceAndPath(MOD_ID, "mirror");

    public static final ResourceKey<Block> MIRROR_BLOCK_KEY =
        ResourceKey.create(Registries.BLOCK, MIRROR_ID);
    public static final ResourceKey<Item> MIRROR_ITEM_KEY =
        ResourceKey.create(Registries.ITEM, MIRROR_ID);

    public static final Block MIRROR_BLOCK = new MirrorBlock(
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.NONE)
            .strength(0.3f)
            .sound(SoundType.GLASS)
            .noOcclusion()
            .setId(MIRROR_BLOCK_KEY)
    );

    public static final Item MIRROR_ITEM = new BlockItem(
        MIRROR_BLOCK,
        new Item.Properties()
            .useBlockDescriptionPrefix()
            .setId(MIRROR_ITEM_KEY)
    );

    /**
     * Block entity for the mirror. It carries no data; it exists only so the mirror gets a
     * {@code BlockEntityRenderer} that draws the frame + glass per-frame (keeping the block out of the
     * terrain mesh) and skips the reflection pass - so the mirror never appears in its own reflection.
     */
    public static final BlockEntityType<MirrorBlockEntity> MIRROR_BLOCK_ENTITY =
        FabricBlockEntityTypeBuilder.create(MirrorBlockEntity::new, MIRROR_BLOCK).build();

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.BLOCK, MIRROR_BLOCK_KEY, MIRROR_BLOCK);
        Registry.register(BuiltInRegistries.ITEM, MIRROR_ITEM_KEY, MIRROR_ITEM);
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, MIRROR_ID, MIRROR_BLOCK_ENTITY);
    }
}
