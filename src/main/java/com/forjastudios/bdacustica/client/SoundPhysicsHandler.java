package com.forjastudios.bdacustica.client;

import com.forjastudios.bdacustica.BDAcusticaFinalMod;
import com.forjastudios.bdacustica.config.BDAcusticaFinalConfig;
import com.forjastudios.bdacustica.engine.AcousticCache;
import com.forjastudios.bdacustica.engine.AcousticRaytracer;
import com.forjastudios.bdacustica.engine.AcousticResult;
import com.forjastudios.bdacustica.engine.AtmosphericState;
import com.forjastudios.bdacustica.engine.EchoTap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public class SoundPhysicsHandler {

    private static final Logger LOGGER = BDAcusticaFinalMod.LOGGER;
    private final AcousticCache cache = new AcousticCache();
    private final Map<SoundInstance, StreamingEntry> streaming = new ConcurrentHashMap<>();
    private final List<PendingEcho> pendingEchoes = new ArrayList<>();
    private int rayBudgetUsedThisTick = 0;
    private long lastTelemetryTick = 0;
    private int totalRaysThisSecond = 0;
    private int normalSoundsThisSecond = 0;
    private int streamingSoundsThisSecond = 0;
    private int echoesThisSecond = 0;

    private record StreamingEntry(SoundInstance original, long lastUpdateTick, AcousticResult lastResult) {}
    private record PendingEcho(SoundInstance original, EchoTap tap, long playTick) {}

    @SubscribeEvent
    public void onPlaySound(PlaySoundEvent event) {
        if (event.getSound() == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        SoundInstance original = event.getSound();
        if (original.isRelative() || original.getAttenuation() == SoundInstance.Attenuation.NONE) return;

        // Skip UI / music / ambient that should not be spatialized heavily
        String path = original.getLocation().getPath();
        if (path.contains("music") || path.contains("ui.") || path.contains("menu")) return;

        boolean isStreaming = original.isLooping() || path.contains("ambient") || path.contains("weather") || path.contains("portal");
        if (isStreaming && !BDAcusticaFinalConfig.ENABLE_STREAMING_SOUNDS.get()) return;

        Vec3 sourcePos = new Vec3(original.getX(), original.getY(), original.getZ());
        Vec3 listenerPos = mc.player.position().add(0, mc.player.getEyeHeight(), 0);
        boolean inWater = mc.player.isUnderWater() || mc.player.isInWater();

        AtmosphericState atmosphere = BDAcusticaFinalConfig.ENABLE_ATMOSPHERE_PHYSICS.get()
                ? AtmosphericState.sample(mc.level, listenerPos, inWater)
                : AtmosphericState.standard();

        long gameTick = mc.level.getGameTime();
        AcousticResult result = null;

        if (BDAcusticaFinalConfig.ENABLE_CACHE.get()) {
            result = cache.get(sourcePos, listenerPos, gameTick);
        }

        if (result == null) {
            int rays = BDAcusticaFinalConfig.RAYS_PER_SOUND.get();
            int budget = BDAcusticaFinalConfig.RAY_BUDGET_PER_TICK.get();
            if (rayBudgetUsedThisTick + rays > budget) {
                rays = Math.max(64, budget - rayBudgetUsedThisTick);
            }
            if (rays < 32) return; // skip if no budget

            result = AcousticRaytracer.trace(
                    mc.level, sourcePos, listenerPos, atmosphere,
                    rays,
                    BDAcusticaFinalConfig.MAX_BOUNCES.get(),
                    BDAcusticaFinalConfig.MAX_RAY_DISTANCE.get(),
                    BDAcusticaFinalConfig.ENABLE_DIFFRACTION.get(),
                    BDAcusticaFinalConfig.ENABLE_DOOR_AND_OPENING_PHYSICS.get(),
                    BDAcusticaFinalConfig.MAX_ECHO_TAPS_PER_SOUND.get()
            );
            rayBudgetUsedThisTick += result.raysExecuted();
            totalRaysThisSecond += result.raysExecuted();

            if (BDAcusticaFinalConfig.ENABLE_CACHE.get()) {
                cache.put(sourcePos, listenerPos, gameTick, result);
            }
        }

        if (isStreaming) {
            streaming.put(original, new StreamingEntry(original, gameTick, result));
            streamingSoundsThisSecond++;
        } else {
            normalSoundsThisSecond++;
        }

        // Replace the sound with modified version
        ModifiedSoundInstance modified = new ModifiedSoundInstance(original, result);
        event.setSound(modified);

        // Schedule echoes
        if (!result.echoes().isEmpty()) {
            int maxActive = BDAcusticaFinalConfig.MAX_ACTIVE_ECHOES.get();
            for (EchoTap tap : result.echoes()) {
                if (pendingEchoes.size() >= maxActive) break;
                long playAt = gameTick + tap.delayTicks();
                pendingEchoes.add(new PendingEcho(original, tap, playAt));
                echoesThisSecond++;
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            streaming.clear();
            pendingEchoes.clear();
            cache.clear();
            return;
        }

        long gameTick = mc.level.getGameTime();
        rayBudgetUsedThisTick = 0;

        // Process pending echoes
        Iterator<PendingEcho> it = pendingEchoes.iterator();
        while (it.hasNext()) {
            PendingEcho pe = it.next();
            if (gameTick >= pe.playTick()) {
                SoundManager sm = mc.getSoundManager();
                ModifiedSoundInstance echoSound = new ModifiedSoundInstance(pe.original(), pe.tap());
                sm.play(echoSound);
                it.remove();
            }
        }

        // Update streaming sounds
        if (BDAcusticaFinalConfig.ENABLE_STREAMING_SOUNDS.get() && BDAcusticaFinalConfig.ENABLE_CONTINUOUS_TRACKING.get()) {
            int interval = BDAcusticaFinalConfig.STREAMING_UPDATE_INTERVAL_TICKS.get();
            Iterator<Map.Entry<SoundInstance, StreamingEntry>> sit = streaming.entrySet().iterator();
            while (sit.hasNext()) {
                Map.Entry<SoundInstance, StreamingEntry> e = sit.next();
                StreamingEntry se = e.getValue();
                if (gameTick - se.lastUpdateTick() >= interval) {
                    // Re-trace and the engine will pick it up on next play, for simplicity we just refresh cache
                    sit.remove();
                }
            }
        }

        // Telemetry
        if (BDAcusticaFinalConfig.ENABLE_TELEMETRY_LOG.get() && gameTick - lastTelemetryTick >= 100) { // ~5 seconds
            LOGGER.info("[BD Acustica Ultra] Telemetry 5s | rays={} normal={} streaming={} echoes={} pendingEchoes={}",
                    totalRaysThisSecond, normalSoundsThisSecond, streamingSoundsThisSecond, echoesThisSecond, pendingEchoes.size());
            totalRaysThisSecond = 0;
            normalSoundsThisSecond = 0;
            streamingSoundsThisSecond = 0;
            echoesThisSecond = 0;
            lastTelemetryTick = gameTick;
        }
    }
}
