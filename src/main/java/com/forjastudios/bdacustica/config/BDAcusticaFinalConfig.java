package com.forjastudios.bdacustica.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class BDAcusticaFinalConfig {

    public static final ForgeConfigSpec CLIENT_SPEC;

    public static final ForgeConfigSpec.IntValue RAYS_PER_SOUND;
    public static final ForgeConfigSpec.IntValue MAX_BOUNCES;
    public static final ForgeConfigSpec.DoubleValue MAX_RAY_DISTANCE;
    public static final ForgeConfigSpec.IntValue RAY_BUDGET_PER_TICK;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_ECHOES;
    public static final ForgeConfigSpec.IntValue MAX_ECHO_TAPS_PER_SOUND;
    public static final ForgeConfigSpec.BooleanValue ENABLE_STREAMING_SOUNDS;
    public static final ForgeConfigSpec.IntValue STREAMING_UPDATE_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_DOOR_AND_OPENING_PHYSICS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_DIFFRACTION;
    public static final ForgeConfigSpec.BooleanValue ENABLE_ATMOSPHERE_PHYSICS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CONTINUOUS_TRACKING;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CACHE;
    public static final ForgeConfigSpec.BooleanValue ENABLE_TELEMETRY_LOG;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("bd_acustica_ultra");

        RAYS_PER_SOUND = builder
                .comment("Autor: Santiago | Rayos omnidireccionales por fuente de sonido (por defecto al tope: 4096, rango 64-8192)")
                .defineInRange("raysPerSound", 4096, 64, 8192);

        MAX_BOUNCES = builder
                .comment("Autor: Santiago | Rebotes maximos por rayo en superficies y detalles arquitectonicos (por defecto 16, rango 1-20)")
                .defineInRange("maxBounces", 16, 1, 20);

        MAX_RAY_DISTANCE = builder
                .comment("Autor: Santiago | Distancia maxima por segmento de rayo en bloques (por defecto 192.0)")
                .defineInRange("maxRayDistance", 192.0, 16.0, 320.0);

        RAY_BUDGET_PER_TICK = builder
                .comment("Autor: Santiago | Presupuesto total de rayos por tick en cliente (por defecto 32768)")
                .defineInRange("rayBudgetPerTick", 32768, 512, 65536);

        MAX_ACTIVE_ECHOES = builder
                .comment("Autor: Santiago | Maximo de taps de eco simultaneos en cola (por defecto 256)")
                .defineInRange("maxActiveEchoes", 256, 8, 512);

        MAX_ECHO_TAPS_PER_SOUND = builder
                .comment("Autor: Santiago | Cantidad maxima de taps de eco por sonido (por defecto 6)")
                .defineInRange("maxEchoTapsPerSound", 6, 1, 6);

        ENABLE_STREAMING_SOUNDS = builder
                .comment("Autor: Santiago | Aplica fisicas acusticas completas a sonidos en streaming")
                .define("enableStreamingSounds", true);

        STREAMING_UPDATE_INTERVAL_TICKS = builder
                .comment("Autor: Santiago | Cada cuantos ticks se re-evalua el entorno acustico para sonidos en streaming activos")
                .defineInRange("streamingUpdateIntervalTicks", 3, 1, 20);

        ENABLE_DOOR_AND_OPENING_PHYSICS = builder
                .comment("Autor: Santiago | Evalua estado abierto/cerrado de puertas, escotillas, portones, rejas y cristales")
                .define("enableDoorAndOpeningPhysics", true);

        ENABLE_DIFFRACTION = builder
                .comment("Autor: Santiago | Activa difraccion multi-anillo alrededor de bordes, vanos y esquinas")
                .define("enableDiffraction", true);

        ENABLE_ATMOSPHERE_PHYSICS = builder
                .comment("Autor: Santiago | Activa presion barometrica por altitud Y, clima/tormenta, densidad del aire y medio acuatico")
                .define("enableAtmospherePhysics", true);

        ENABLE_CONTINUOUS_TRACKING = builder
                .comment("Autor: Santiago | Seguimiento continuo de fuentes activas en bucle y en movimiento")
                .define("enableContinuousTracking", true);

        ENABLE_CACHE = builder
                .comment("Autor: Santiago | Cache espacial LRU (por defecto false para recalculo fresco en tiempo real)")
                .define("enableCache", false);

        ENABLE_TELEMETRY_LOG = builder
                .comment("Autor: Santiago | Registra telemetria de rayos, sonidos normales, streaming y ecos cada 5 segundos")
                .define("enableTelemetryLog", true);

        builder.pop();
        CLIENT_SPEC = builder.build();
    }

    private BDAcusticaFinalConfig() {}
}
