package com.forjastudios.bdacustica.client;

import com.forjastudios.bdacustica.engine.AcousticResult;
import com.forjastudios.bdacustica.engine.EchoTap;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class ModifiedSoundInstance extends AbstractSoundInstance {

    private final SoundInstance delegate;

    public ModifiedSoundInstance(SoundInstance original, AcousticResult result) {
        super(original.getLocation(), original.getSource(), RandomSource.create());
        this.delegate = original;
        this.looping = original.isLooping();
        this.delay = original.getDelay();
        this.attenuation = original.getAttenuation();
        this.relative = original.isRelative();

        Vec3 pos = result.apparentPosition();
        this.x = pos.x;
        this.y = pos.y;
        this.z = pos.z;

        this.volume = Math.max(0.012f, original.getVolume() * result.volumeMultiplier());
        this.pitch = Math.max(0.50f, original.getPitch() * result.pitchMultiplier());
    }

    public ModifiedSoundInstance(SoundInstance original, EchoTap tap) {
        super(original.getLocation(), original.getSource(), RandomSource.create());
        this.delegate = original;
        this.looping = false;
        this.delay = 0;
        this.attenuation = original.getAttenuation();
        this.relative = false;

        Vec3 pos = tap.apparentPosition();
        this.x = pos.x;
        this.y = pos.y;
        this.z = pos.z;

        this.volume = Math.max(0.010f, original.getVolume() * tap.gain());
        this.pitch = Math.max(0.50f, original.getPitch() * tap.pitchScale());
    }

    @Nullable
    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        WeighedSoundEvents resolved = delegate.resolve(manager);
        this.sound = delegate.getSound();
        return resolved;
    }
}
