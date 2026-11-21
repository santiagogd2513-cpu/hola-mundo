package com.forjastudios.bdacustica;

import com.forjastudios.bdacustica.client.SoundPhysicsHandler;
import com.forjastudios.bdacustica.config.BDAcusticaFinalConfig;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(BDAcusticaFinalMod.MOD_ID)
public class BDAcusticaFinalMod {
    public static final String MOD_ID = "bdacustica";
    public static final String AUTHOR = "Santiago";
    public static final String DESCRIPTION = "Motor acustico ultra realista complementado para Beautiful Decor v7.39.3.";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BDAcusticaFinalMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, BDAcusticaFinalConfig.CLIENT_SPEC);

        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> () -> {
            MinecraftForge.EVENT_BUS.register(new SoundPhysicsHandler());
            LOGGER.info("[BD Acustica Ultra v3.0.0-final-ultra-bd7393 | Autor: {}] {}", AUTHOR, DESCRIPTION);
        });
    }
}
