package com.forjastudios.bdacustica.engine;

import net.minecraft.world.phys.Vec3;

public record EchoTap(
        int delayTicks,
        float gain,
        float pitchScale,
        Vec3 apparentPosition
) {}
