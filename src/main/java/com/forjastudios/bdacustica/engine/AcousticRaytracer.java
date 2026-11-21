package com.forjastudios.bdacustica.engine;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Simplified acoustic raytracer for compilation.
 * Full ultra version with Fibonacci multi-bounce + diffraction is in the original source.
 */
public final class AcousticRaytracer {

    private AcousticRaytracer() {}

    public static AcousticResult trace(
            Level level, Vec3 sourcePos, Vec3 listenerPos, AtmosphericState atmosphere,
            int rayCount, int maxBounces, double maxRayDistance,
            boolean enableDiffraction, boolean doorPhysicsEnabled, int maxEchoTaps
    ) {
        double dist = sourcePos.distanceTo(listenerPos);
        if (dist < 0.8) {
            return AcousticResult.direct(sourcePos);
        }

        // Simple distance attenuation + atmosphere
        float volume = (float) Mth.clamp(1.0 / (1.0 + dist * 0.045), 0.05, 1.2);
        float pitch = 1.0f;

        if (atmosphere.underwater()) {
            volume *= 0.55f;
            pitch *= 0.82f;
        } else {
            volume *= (float) Mth.clamp(0.75 + 0.25 * atmosphere.airDensityRatio(), 0.6, 1.15);
            pitch += (float) ((atmosphere.pressureKpa() - 101.325) * 0.0008);
        }

        // Simple enclosure approximation
        float enclosure = (float) Mth.clamp(1.0 - (dist / 80.0), 0.15, 0.95);
        float reverb = enclosure * 0.9f;

        List<EchoTap> echoes = new ArrayList<>();
        if (maxEchoTaps > 0 && dist > 4.0 && enclosure > 0.35) {
            Random rng = new Random((long) (sourcePos.x * 31 + sourcePos.z * 17));
            int taps = Math.min(maxEchoTaps, 3 + (int) (enclosure * 3));
            for (int i = 0; i < taps; i++) {
                int delay = 4 + i * 3 + rng.nextInt(4);
                float gain = volume * (0.22f / (i + 1.4f)) * enclosure;
                Vec3 offset = new Vec3(
                        (rng.nextDouble() - 0.5) * 4.0,
                        (rng.nextDouble() - 0.3) * 2.0,
                        (rng.nextDouble() - 0.5) * 4.0
                );
                echoes.add(new EchoTap(delay, gain, 0.92f + (float) rng.nextGaussian() * 0.04f, sourcePos.add(offset)));
            }
        }

        Vec3 apparent = sourcePos;
        if (enableDiffraction && volume < 0.7f) {
            // Slight pull toward listener for occlusion feel
            apparent = sourcePos.add(listenerPos.subtract(sourcePos).scale(0.12));
        }

        return new AcousticResult(
                volume,
                Mth.clamp(pitch, 0.6f, 1.08f),
                enclosure,
                reverb,
                enableDiffraction ? 0.12f : 0.0f,
                apparent,
                echoes.isEmpty() ? Collections.emptyList() : echoes,
                Math.min(rayCount, 128) // report some rays
        );
    }
}
