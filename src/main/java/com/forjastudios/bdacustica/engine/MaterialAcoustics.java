package com.forjastudios.bdacustica.engine;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

public final class MaterialAcoustics {

    private MaterialAcoustics() {}

    public record AcousticProps(
            float absLow,
            float absMid,
            float absHigh,
            float reflectivity,
            float scattering,
            float diffraction
    ) {
        public float averageAbsorption() {
            return (absLow + absMid + absHigh) / 3.0f;
        }
    }

    public static AcousticProps getProps(BlockState state, boolean doorPhysicsEnabled) {
        if (state.isAir()) {
            return new AcousticProps(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }

        String blockClassName = state.getBlock().getClass().getSimpleName();

        if (doorPhysicsEnabled && (state.getBlock() instanceof DoorBlock || "HingedDoorBlock".equals(blockClassName) || "ElevatorDoorBlock".equals(blockClassName))) {
            boolean open = state.hasProperty(BlockStateProperties.OPEN) && Boolean.TRUE.equals(state.getValue(BlockStateProperties.OPEN));
            if (open) {
                return new AcousticProps(0.03f, 0.06f, 0.10f, 0.18f, 0.35f, 0.85f);
            }
            boolean metalDoor = state.getSoundType() == SoundType.METAL || "ElevatorDoorBlock".equals(blockClassName);
            return metalDoor
                    ? new AcousticProps(0.42f, 0.72f, 0.88f, 0.88f, 0.10f, 0.35f)
                    : new AcousticProps(0.28f, 0.54f, 0.74f, 0.55f, 0.25f, 0.45f);
        }

        if (doorPhysicsEnabled && "GarageDoorBlock".equals(blockClassName)) {
            boolean open = state.hasProperty(BlockStateProperties.OPEN) && Boolean.TRUE.equals(state.getValue(BlockStateProperties.OPEN));
            int raise = 0;
            for (var prop : state.getProperties()) {
                if (prop instanceof IntegerProperty ip && "raise".equals(ip.getName())) {
                    raise = state.getValue(ip);
                    break;
                }
            }
            if (open || raise >= 10) {
                return new AcousticProps(0.05f, 0.10f, 0.16f, 0.24f, 0.35f, 0.80f);
            }
            float closedRatio = Math.max(0.15f, 1.0f - (raise / 12.0f));
            return new AcousticProps(0.36f * closedRatio, 0.62f * closedRatio, 0.80f * closedRatio, 0.85f, 0.14f, 0.38f);
        }

        if (doorPhysicsEnabled && (state.getBlock() instanceof TrapDoorBlock || "ApplianceDoorBlock".equals(blockClassName))) {
            boolean open = state.hasProperty(BlockStateProperties.OPEN) && Boolean.TRUE.equals(state.getValue(BlockStateProperties.OPEN));
            if (open) {
                return new AcousticProps(0.04f, 0.08f, 0.14f, 0.22f, 0.35f, 0.80f);
            }
            boolean metalTrap = state.getSoundType() == SoundType.METAL || "ApplianceDoorBlock".equals(blockClassName);
            return metalTrap
                    ? new AcousticProps(0.38f, 0.66f, 0.84f, 0.86f, 0.12f, 0.35f)
                    : new AcousticProps(0.24f, 0.48f, 0.68f, 0.50f, 0.28f, 0.45f);
        }

        if (doorPhysicsEnabled && state.getBlock() instanceof FenceGateBlock) {
            boolean open = Boolean.TRUE.equals(state.getValue(FenceGateBlock.OPEN));
            if (open) {
                return new AcousticProps(0.02f, 0.05f, 0.08f, 0.10f, 0.35f, 0.85f);
            }
            return new AcousticProps(0.16f, 0.32f, 0.48f, 0.42f, 0.35f, 0.55f);
        }

        if (state.getBlock() instanceof IronBarsBlock) {
            return new AcousticProps(0.05f, 0.14f, 0.26f, 0.58f, 0.22f, 0.65f);
        }

        if (state.is(Blocks.WATER)) {
            return new AcousticProps(0.08f, 0.22f, 0.45f, 0.26f, 0.15f, 0.58f);
        }
        if (state.is(Blocks.LAVA)) {
            return new AcousticProps(0.18f, 0.35f, 0.58f, 0.32f, 0.25f, 0.42f);
        }

        if (!state.blocksMotion()) {
            if (state.is(Blocks.COBWEB)) {
                return new AcousticProps(0.08f, 0.22f, 0.42f, 0.12f, 0.70f, 0.65f);
            }
            return new AcousticProps(0.01f, 0.03f, 0.06f, 0.04f, 0.42f, 0.75f);
        }

        if (state.is(BlockTags.WOOL_CARPETS)) {
            return new AcousticProps(0.22f, 0.56f, 0.80f, 0.12f, 0.72f, 0.35f);
        }
        if (state.is(BlockTags.WOOL)) {
            return new AcousticProps(0.38f, 0.78f, 0.95f, 0.08f, 0.74f, 0.18f);
        }

        if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SOUL_SAND)) {
            return new AcousticProps(0.30f, 0.58f, 0.80f, 0.17f, 0.66f, 0.28f);
        }

        if (state.is(BlockTags.LEAVES)) {
            return new AcousticProps(0.06f, 0.16f, 0.32f, 0.14f, 0.76f, 0.66f);
        }

        if (state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)) {
            return new AcousticProps(0.13f, 0.21f, 0.27f, 0.93f, 0.03f, 0.46f);
        }
        // Fixed: GLASS_PANES tag resolution issue on this mapping set
        if (state.is(Blocks.GLASS) || state.is(Blocks.TINTED_GLASS) || state.is(Blocks.GLASS_PANE) ||
            state.getBlock().getClass().getSimpleName().contains("GlassPane") || state.getBlock().getClass().getSimpleName().contains("StainedGlassPane")) {
            return new AcousticProps(0.11f, 0.20f, 0.32f, 0.79f, 0.05f, 0.38f);
        }

        if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN) || state.is(Blocks.BEDROCK)) {
            return new AcousticProps(0.64f, 0.88f, 0.97f, 0.89f, 0.10f, 0.24f);
        }

        SoundType sound = state.getSoundType();
        if (sound == SoundType.METAL || sound == SoundType.ANVIL) {
            return new AcousticProps(0.30f, 0.50f, 0.66f, 0.91f, 0.07f, 0.42f);
        }
        if (sound == SoundType.AMETHYST || sound == SoundType.AMETHYST_CLUSTER) {
            return new AcousticProps(0.24f, 0.44f, 0.58f, 0.90f, 0.11f, 0.46f);
        }
        if (sound == SoundType.STONE || sound == SoundType.DEEPSLATE) {
            return new AcousticProps(0.34f, 0.58f, 0.76f, 0.87f, 0.24f, 0.50f);
        }
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS) || sound == SoundType.WOOD) {
            return new AcousticProps(0.20f, 0.38f, 0.58f, 0.52f, 0.32f, 0.54f);
        }
        if (sound == SoundType.SAND || sound == SoundType.GRAVEL || sound == SoundType.MUD) {
            return new AcousticProps(0.26f, 0.48f, 0.68f, 0.26f, 0.56f, 0.38f);
        }

        return new AcousticProps(0.24f, 0.44f, 0.64f, 0.44f, 0.30f, 0.46f);
    }
}
