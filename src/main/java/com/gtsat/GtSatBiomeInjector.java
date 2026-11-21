package com.gtsat;

import dev.worldgen.lithostitched.api.event.AddBiomeInjectorsEvent;
import dev.worldgen.lithostitched.api.worldgen.biomeinjector.BiomeInjector;
import dev.worldgen.lithostitched.api.worldgen.biomeinjector.ParameterBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Lithostitched biome injector (mode = inject) — the v2 replacement for the
 * {@code gtsat:slot} preset biome source.
 *
 * <p>Wiring copied from the two shipped reference implementations
 * {@code natures_spirit} and {@code regions-unexplored}:
 * {@code AddBiomeInjectorsEvent.EVENT.register(listener)} where the listener
 * receives a {@link RegistryAccess} and a
 * {@code BiConsumer<ResourceLocation, BiomeInjector>} sink.</p>
 *
 * <h2>Why {@code dispatchAlternateLayout} and not {@code addPoints}</h2>
 * <p>{@code addPoints} is <b>append-only</b> (lithostitched
 * {@code AddPoints.apply} does a bare {@code addAll}). Vanilla resolves biomes
 * with {@code Climate.ParameterList.findValue}, which is a <i>nearest-point</i>
 * search; both vanilla's own tiling and gtsat's lattice are complete partitions
 * of the climate box, so both score fitness 0 everywhere and the winner is
 * decided by RTree tie-break order. Measured with
 * {@code selftest/AppendCostAnalysis} against the real {@code Climate} classes
 * <i>and the real 7,593-point vanilla parameter list</i>: appended gtsat points
 * won <b>83.13%</b> of samples (5 seeds × 100k), i.e. vanilla kept ~17% of the
 * band gtsat was supposed to own. Not a no-op — simply strictly worse.</p>
 *
 * <p>{@code dispatchAlternateLayout} instead <b>substitutes the whole parameter
 * list</b> whenever its {@link ParameterBuilder} predicate matches, which is the
 * documented way to own the climate layout (this is what natures_spirit uses for
 * its region layouts), and measures <b>100%</b>. We therefore dispatch on the
 * <i>depth</i> axis so that the surface cohort and the underground cohort each
 * replace only their own depth slice and never fight each other.</p>
 *
 * <p>Returns {@code false} from {@link #register()} when lithostitched is not on
 * the runtime classpath; {@link GtSatMod} then degrades to the scheduler path.
 * This class is only classloaded on the inject path, so a missing lithostitched
 * never throws {@code NoClassDefFoundError} at startup.</p>
 */
public final class GtSatBiomeInjector {
    private static final Logger LOGGER = LoggerFactory.getLogger("gtsat");

    /** Injector priority: below natures_spirit (2000/2500) so their river overrides win. */
    private static final int PRIORITY = 1000;

    public static final ResourceLocation INJECTOR_ID_OVERWORLD =
            ResourceLocation.fromNamespaceAndPath(GtSatMod.MOD_ID, "overworld_lattice");
    public static final ResourceLocation INJECTOR_ID_OVERWORLD_UNDERGROUND =
            ResourceLocation.fromNamespaceAndPath(GtSatMod.MOD_ID, "overworld_underground_lattice");
    public static final ResourceLocation INJECTOR_ID_NETHER =
            ResourceLocation.fromNamespaceAndPath(GtSatMod.MOD_ID, "nether_lattice");
    public static final ResourceLocation INJECTOR_ID_END =
            ResourceLocation.fromNamespaceAndPath(GtSatMod.MOD_ID, "end_lattice");

    private GtSatBiomeInjector() {
    }

    /** Registers the event listener; returns false if lithostitched is absent. */
    public static boolean register() {
        try {
            AddBiomeInjectorsEvent.EVENT.register(GtSatBiomeInjector::addInjectors);
            return true;
        } catch (Throwable t) {          // NoClassDefFoundError / linkage error
            LOGGER.error("gtsat: lithostitched AddBiomeInjectorsEvent unavailable; "
                    + "inject mode cannot register. Falling back to scheduler (needs a preset).", t);
            return false;
        }
    }

    /** The lithostitched callback. */
    public static void addInjectors(RegistryAccess registryAccess,
                                    BiConsumer<ResourceLocation, BiomeInjector> sink) {
        BiomeConfigView cfg = BiomeConfigView.read();

        HolderLookup.RegistryLookup<Biome> lookup =
                registryAccess.lookupOrThrow(Registries.BIOME);

        if (!"replace_vanilla_params".equals(cfg.injectPriority)) {
            // append (addPoints) genuinely works, but measured against the real
            // vanilla parameter list it lets gtsat win only 83.13% of samples
            // (5 seeds x 100k, selftest/AppendCostAnalysis) versus 100% for
            // replace — vanilla keeps ~17% of gtsat's own climate band. We do not
            // ship a switch that quietly gives away 1/6 of the box.
            LOGGER.error("gtsat: inject_priority={} is not offered — append gives away ~17% of "
                    + "gtsat's climate band to vanilla biomes (83.13% vs 100.00% measured). "
                    + "Falling back to replace_vanilla_params.", cfg.injectPriority);
        }
        boolean replace = true;     // replace is the only offered mode

        // Depth split honours biomes.underground_max_y, but only after measuring what
        // the live depth axis actually is: grandterrain currently reuses vanilla's
        // 384-block gradient in a 2288-block world, where a fixed 0.5 boundary lands
        // at y=64 instead of the configured y=-40. That case logs an ERROR and uses
        // the closest representable value instead of pretending 0.5 is correct.
        DepthAxisInspector.Probe probe =
                DepthAxisInspector.probe(registryAccess, cfg.undergroundMaxY);
        ClimatePointBuilder.DepthBand[] split =
                ClimatePointBuilder.DepthBand.forWorld(cfg.undergroundMaxY, probe);
        ClimatePointBuilder.DepthBand surfaceBand = split[0];
        ClimatePointBuilder.DepthBand undergroundBand = split[1];

        boolean spatial = probe.grandTerrain;

        CohortBuilder.Cohorts ow = CohortBuilder.build(lookup, CohortBuilder.OVERWORLD, cfg);
        buildAndSubmit(sink, INJECTOR_ID_OVERWORLD, ow.surface(), cfg,
                surfaceBand, replace, "overworld_surface", spatial, ow.underground());
        // Underground cohort: same dimension, deeper depth band; the two bands are
        // strictly disjoint, so each dispatch owns its own slice of the depth axis.
        buildAndSubmit(sink, INJECTOR_ID_OVERWORLD_UNDERGROUND, ow.underground(), cfg,
                undergroundBand, replace, "overworld_underground", false, List.of());

        // ---- nether --------------------------------------------------------
        CohortBuilder.Cohorts ne = CohortBuilder.build(lookup, CohortBuilder.NETHER, cfg);
        buildAndSubmit(sink, INJECTOR_ID_NETHER, ne.dimension(), cfg,
                ClimatePointBuilder.DepthBand.FULL, replace, "nether", spatial, List.of());

        // ---- end -----------------------------------------------------------
        CohortBuilder.Cohorts en = CohortBuilder.build(lookup, CohortBuilder.END, cfg);
        buildAndSubmit(sink, INJECTOR_ID_END, en.dimension(), cfg,
                ClimatePointBuilder.DepthBand.FULL, replace, "end", spatial, List.of());
    }

    private static void buildAndSubmit(BiConsumer<ResourceLocation, BiomeInjector> sink,
                                       ResourceLocation id,
                                       List<Holder<Biome>> biomes,
                                       BiomeConfigView cfg,
                                       ClimatePointBuilder.DepthBand band,
                                       boolean replace,
                                       String label,
                                       boolean spatial,
                                       List<Holder<Biome>> underground) {
        if (biomes.isEmpty()) {
            LOGGER.warn("gtsat: inject cohort {} is empty, skipping", label);
            return;
        }
        ResourceKey<Level> level = levelKey(id).orElse(Level.OVERWORLD);
        Climate.ParameterList<Holder<Biome>> list =
                ClimatePointBuilder.build(biomes, cfg.biomeSizeRatio, band);
        if (spatial) {
            list = new SpatialBiomeLayout(list, level, biomes, underground, cfg.biomeSizeRatio,
                    cfg.undergroundMaxY);
        }

        BiomeInjector.InjectorBuilder b = BiomeInjector.builder(level).priority(PRIORITY);
        BiomeInjector injector = replace
                ? b.dispatchAlternateLayout(depthGate(band), list)
                : b.addPoints(list);
        sink.accept(id, injector);

        BiomeGridScheme.Layout layout = BiomeGridScheme.build(biomes.size(), cfg.biomeSizeRatio);
        LOGGER.info("gtsat: injected {} biomes into {} ({} points, depth=[{},{}], "
                        + "replace={}, spatial={}, lattice {}x{}x{}x{}x{}={})",
                biomes.size(), label, list.values().size(),
                band.min() / 10000.0D, band.max() / 10000.0D, replace, spatial,
                layout.nt(), layout.nh(), layout.nc(), layout.ne(), layout.nw(), layout.total());
    }

    /**
     * Restricts a dispatch to one depth slice. {@code climateMin/Max} take a value
     * in the <b>float</b> climate domain (lithostitched divides the quantised
     * {@code TargetPoint} value by 10000 before comparing), so we convert back.
     */
    static ParameterBuilder depthGate(ClimatePointBuilder.DepthBand band) {
        ParameterBuilder p = ParameterBuilder.create();
        if (band.min() > -10000L) {
            p = p.climateMin(BiomeInjector.ClimateParameter.DEPTH, band.min() / 10000.0D);
        }
        if (band.max() < 10000L) {
            p = p.climateMax(BiomeInjector.ClimateParameter.DEPTH, band.max() / 10000.0D);
        }
        return p;
    }

    /**
     * Turns a cohort + lattice into a vanilla {@link Climate.ParameterList}.
     * Delegates to {@link ClimatePointBuilder}, which has no lithostitched
     * imports and is therefore unit-testable offline.
     */
    public static Climate.ParameterList<Holder<Biome>> buildParameterList(
            List<Holder<Biome>> biomes, double biomeSizeRatio, boolean underground) {
        return ClimatePointBuilder.build(biomes, biomeSizeRatio, underground);
    }

    /** Maps an injector id to the Level it targets. */
    public static Optional<ResourceKey<Level>> levelKey(ResourceLocation id) {
        if (INJECTOR_ID_OVERWORLD.equals(id) || INJECTOR_ID_OVERWORLD_UNDERGROUND.equals(id)) {
            return Optional.of(Level.OVERWORLD);
        }
        if (INJECTOR_ID_NETHER.equals(id)) {
            return Optional.of(Level.NETHER);
        }
        if (INJECTOR_ID_END.equals(id)) {
            return Optional.of(Level.END);
        }
        return Optional.empty();
    }

    /** Diagnostic: the biome ids gtsat would claim, without a running game. */
    public static Set<ResourceLocation> injectedBiomeIds(RegistryAccess registryAccess) {
        BiomeConfigView cfg = BiomeConfigView.read();
        HolderLookup.RegistryLookup<Biome> lookup = registryAccess.lookupOrThrow(Registries.BIOME);
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (CohortBuilder.Cohorts c : List.of(
                CohortBuilder.build(lookup, CohortBuilder.OVERWORLD, cfg),
                CohortBuilder.build(lookup, CohortBuilder.NETHER, cfg),
                CohortBuilder.build(lookup, CohortBuilder.END, cfg))) {
            c.surface().forEach(h -> out.add(idOf(h)));
            c.underground().forEach(h -> out.add(idOf(h)));
            if (c.dimension() != c.surface() && c.dimension() != c.underground()) {
                c.dimension().forEach(h -> out.add(idOf(h)));
            }
        }
        return out;
    }

    private static ResourceLocation idOf(Holder<Biome> h) {
        return h.unwrapKey().map(k -> k.location()).orElse(null);
    }
}
