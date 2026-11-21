package com.forjastudios.bdacustica.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

public record AtmosphericState(
        double pressureKpa,
        double temperatureCelsius,
        double airDensityRatio,
        double speedOfSoundBlocksPerSec,
        double lowFreqAbsorptionPerBlock,
        double midFreqAbsorptionPerBlock,
        double highFreqAbsorptionPerBlock,
        boolean underwater,
        boolean thundering,
        boolean raining
) {
    private static final double SEA_LEVEL_Y = 63.0;
    private static final double SEA_LEVEL_PRESSURE_KPA = 101.325;

    public static AtmosphericState sample(Level level, Vec3 listenerPos, boolean listenerInWater) {
        if (listenerInWater) {
            return new AtmosphericState(180.0, 11.0, 840.0, 1484.0, 0.006, 0.019, 0.050, true, false, false);
        }

        BlockPos pos = BlockPos.containing(listenerPos);
        double altitudeFromSea = listenerPos.y - SEA_LEVEL_Y;

        boolean raining = level.isRainingAt(pos) || level.isRaining();
        boolean thundering = level.isThundering();

        double weatherPressureDeltaKpa = thundering ? -3.0 : (raining ? -1.4 : 0.0);
        double pressureKpa = SEA_LEVEL_PRESSURE_KPA * Math.exp(-altitudeFromSea / 7900.0) + weatherPressureDeltaKpa;
        pressureKpa = Mth.clamp(pressureKpa, 24.0, 130.0);

        Holder<Biome> biomeHolder = level.getBiome(pos);
        float baseTemp = biomeHolder.value().getBaseTemperature();
        double tempC = (baseTemp * 22.0) - (Math.max(0.0, altitudeFromSea) * 0.035) - (raining ? 3.0 : 0.0);
        tempC = Mth.clamp(tempC, -40.0, 55.0);
        double tempK = tempC + 273.15;

        double speedOfSound = 20.05 * Math.sqrt(tempK);

        double densityRatio = (pressureKpa / SEA_LEVEL_PRESSURE_KPA) * (288.15 / tempK);
        densityRatio = Mth.clamp(densityRatio, 0.20, 1.55);

        double humidityMultiplier = thundering ? 1.90 : (raining ? 1.52 : (biomeHolder.value().hasPrecipitation() ? 1.07 : 0.78));

        double lowAbs = 0.0010 * densityRatio;
        double midAbs = 0.0038 * densityRatio * humidityMultiplier;
        double highAbs = 0.0125 * densityRatio * humidityMultiplier;

        return new AtmosphericState(
                pressureKpa, tempC, densityRatio, speedOfSound,
                lowAbs, midAbs, highAbs, false, thundering, raining
        );
    }

    public static AtmosphericState standard() {
        return new AtmosphericState(SEA_LEVEL_PRESSURE_KPA, 15.0, 1.0, 343.0, 0.0010, 0.0038, 0.0125, false, false, false);
    }
}
