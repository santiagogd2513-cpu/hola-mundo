package com.forjastudios.bdacustica.engine;

import net.minecraft.world.phys.Vec3;
import java.util.Collections;
import java.util.List;

public record AcousticResult(
        float volumeMultiplier,
        float pitchMultiplier,
        float roomEnclosureFactor,
        float reverbTailSeconds,
        float diffractionBoost,
        Vec3 apparentPosition,
        List<EchoTap> echoes,
        int raysExecuted
) {
    public static AcousticResult direct(Vec3 sourcePos) {
        return new AcousticResult(1.0f, 1.0f, 0.0f, 0.0f, 0.0f, sourcePos, Collections.emptyList(), 0);
    }
}
