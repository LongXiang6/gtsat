package com.gtsat;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the Grand Terrain biome dispatcher.
 *
 * <p>Two modes, selected by {@code [biomes].mode} in the shared grandterrain
 * config (architecture §3), read through {@link BiomeConfigView}:</p>
 * <ul>
 *   <li><b>inject</b> (default, v2): {@link GtSatBiomeInjector} pushes our
 *   climate points into vanilla {@code minecraft:multi_noise} through
 *   lithostitched, so <em>normal world creation</em> gets all biomes with no
 *   preset selection. Requires lithostitched (soft dependency).</li>
 *   <li><b>scheduler</b> (v1, kept for debugging): the old hash/cycle
 *   {@link SlotBiomeSource} is still registered as a biome-source type, so a
 *   world can reference it, but <b>no world preset ships any more</b>. The
 *   shipped {@code grand_terrain_slot} preset was removed because it referenced
 *   {@code grandterrain:grand_terrain / grand_nether / grand_end}, ids that no
 *   jar defines — merely having that file in the pack made datapack loading fail
 *   with {@code IllegalStateException: Unbound values in registry}, so a world
 *   could not be created at all. See {@code EVIDENCE.md}.</li>
 * </ul>
 *
 * <p>If {@code mode=inject} is requested but lithostitched is absent, we log an
 * ERROR and leave the scheduler path registered; we never crash because a soft
 * dependency is missing.</p>
 */
@Mod(GtSatMod.MOD_ID)
public final class GtSatMod {
    public static final String MOD_ID = "gtsat";
    private static final Logger LOGGER = LoggerFactory.getLogger("gtsat");
    private static final String LITHOSTITCHED_MODID = "lithostitched";

    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, MOD_ID);
    public static final DeferredHolder<MapCodec<? extends BiomeSource>, MapCodec<SlotBiomeSource>> SLOT =
            BIOME_SOURCES.register("slot", () -> SlotBiomeSource.CODEC);

    /** Thin alias so callers/tests can keep using the historical name. */
    public static ModeDecision.Plan decideMode(String mode, boolean lithostitchedLoaded,
                                               boolean registrationOk) {
        return ModeDecision.decide(mode, lithostitchedLoaded, registrationOk);
    }

    public GtSatMod(IEventBus modEventBus) {
        // Keep the biome-source type registered so existing worlds that reference
        // gtsat:slot still decode. No datapack file ships that reference.
        BIOME_SOURCES.register(modEventBus);

        BiomeConfigView cfg = BiomeConfigView.read();
        LOGGER.info("gtsat: bootstrap {}", cfg);

        boolean lithostitchedLoaded = ModList.get().isLoaded(LITHOSTITCHED_MODID);
        boolean registrationOk = false;
        if (!"scheduler".equalsIgnoreCase(cfg.mode) && lithostitchedLoaded) {
            registrationOk = GtSatBiomeInjector.register();
        }

        switch (decideMode(cfg.mode, lithostitchedLoaded, registrationOk)) {
            case SCHEDULER_EXPLICIT -> {
                cfg.applyToSchedulerTables();
                LOGGER.warn("gtsat: mode=scheduler — the gtsat:slot biome source is registered, "
                        + "but gtsat ships no world preset any more (the old one referenced "
                        + "grandterrain ids that no longer exist and broke world creation). "
                        + "Use mode=inject for normal play; scheduler is a debugging path only.");
            }
            case SCHEDULER_FALLBACK_NO_LITHO -> {
                LOGGER.error("gtsat: mode=inject but lithostitched is not installed, so biome "
                        + "points cannot be injected and no preset ships as a fallback. "
                        + "Install lithostitched 1.8.1+ and use mode=inject. "
                        + "(mode=scheduler is kept for debugging only.)");
                cfg.applyToSchedulerTables();
            }
            case SCHEDULER_FALLBACK_REGISTRATION_FAILED -> {
                LOGGER.error("gtsat: lithostitched present but injection registration failed; "
                        + "no preset fallback ships. Check the log above for the cause.");
                cfg.applyToSchedulerTables();
            }
            case INJECT -> LOGGER.info("gtsat: inject mode active — all cohorts flow into "
                    + "minecraft:multi_noise on normal world creation (no preset).");
        }
    }
}
