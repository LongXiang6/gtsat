package com.gtsat;

import com.grandterrain.worldgen.hydro.OpenOcean;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates the {@code depth} density function grandterrain actually ships, so gtsat
 * can tell whether the depth axis is appropriate for a 2288-tall world.
 *
 * <h2>The problem this exists to catch</h2>
 * <p>grandterrain's noise settings override {@code data/minecraft/worldgen/noise_settings/overworld.json}
 * and set {@code noise_router.depth} to {@code "minecraft:overworld/depth"} — a
 * {@code y_clamped_gradient(-64 → 1.5, 320 → -1.5)} built for vanilla's 384-tall
 * overworld. In a {@code min_y=-256, height=2288} world it <b>saturates</b>: it only
 * varies across 384 of 2,288 blocks (16.8%) and is a constant everywhere else.</p>
 *
 * <p>Consequence: a fixed {@code depth = 0.5} boundary lands at <b>y=64</b> (sea
 * level), while {@code biomes.underground_max_y} asks for <b>y=-40</b> — a 104-block
 * misplacement. Underground biomes would start 104 blocks too high.</p>
 *
 * <h2>Why this evaluates instead of reading JSON</h2>
 * <p>An earlier version of this class parsed the noise-settings JSON looking for the
 * literal string {@code "minecraft:overworld/depth"}. That is brittle: the router
 * entry may be a reference, an inline object, or wrapped. Worse, grandterrain
 * overrides the vanilla namespace, so a string match says nothing about what the
 * function <i>computes</i>. Evaluating the loaded {@link DensityFunction} at sample Y
 * values measures the actual behaviour, which is what the split depends on.</p>
 */
public final class DepthAxisInspector {
    private static final Logger LOGGER = LoggerFactory.getLogger("gtsat");

    /** grandterrain world dimensions; a gradient built for 384 blocks cannot cope. */
    public static final int WORLD_MIN_Y = -256;
    public static final int WORLD_HEIGHT = 2288;
    public static final int WORLD_TOP = WORLD_MIN_Y + WORLD_HEIGHT - 1;

    /** Vanilla's clamped gradient domain, reproduced here for reference arithmetic. */
    public static final int VANILLA_GRADIENT_FROM_Y = -64;
    public static final int VANILLA_GRADIENT_TO_Y = 320;
    public static final double VANILLA_GRADIENT_FROM_VALUE = 1.5D;
    public static final double VANILLA_GRADIENT_TO_VALUE = -1.5D;

    /** Sample points used to fingerprint the depth axis. */
    private static final int[] PROBE_YS = {
            WORLD_MIN_Y, -128, -64, -40, 0, 64, 320, 1024, WORLD_TOP
    };

    /** The axis the depth function turns out to be. */
    public enum DepthAxis {
        /**
         * Tracks vanilla's clamped gradient: flat 1.5 at/below -64, flat -1.5 at/above
         * 320. Unfit for a 2288-tall world.
         */
        VANILLA_CLAMPED_GRADIENT,
        /**
         * grandterrain's own piecewise axis. Verified anchors:
         * depth(-256)=1.5, depth(-40)=0.2, depth(64)=0.0, depth(2031)=-1.5.
         */
        GRANDTERRAIN_PIECEWISE,
        /** Varies with height but does not match the known grandterrain anchors. */
        OTHER_DIMENSION_AWARE,
        /** No noise router available (registry not consulted yet). */
        UNKNOWN
    }

    /** Expected anchor values of grandterrain's piecewise depth axis. */
    public static final int[][] GRANDTERRAIN_ANCHORS = {
            {-256, 1, 2},   // y, numerator, denominator -> 1.5
            {-40, 1, 5},    // 0.2
            {64, 0, 1},     // 0.0
            {WORLD_TOP, -3, 2}, // -1.5
    };

    /** Tolerance for anchor comparison. */
    private static final double ANCHOR_EPS = 1e-6;

    /** Result of probing: the classification plus the measured numbers. */
    public static final class Probe {
        public final DepthAxis axis;
        public final boolean grandTerrain;
        public final double depthAtUndergroundMaxY;
        /** Y where depth equals the configured split value, best effort. */
        public final String detail;

        Probe(DepthAxis axis, boolean grandTerrain, double depthAtUndergroundMaxY, String detail) {
            this.axis = axis;
            this.grandTerrain = grandTerrain;
            this.depthAtUndergroundMaxY = depthAtUndergroundMaxY;
            this.detail = detail;
        }
    }

    private DepthAxisInspector() {
    }

    /**
     * Probes the depth router from the live registries.
     *
     * @param registryAccess registries handed to us by lithostitched
     * @param undergroundMaxY the configured surface/underground boundary Y
     */
    public static Probe probe(RegistryAccess registryAccess, int undergroundMaxY) {
        DensityFunction depth;
        boolean grandTerrain;
        try {
            var settingsLookup = registryAccess.lookupOrThrow(Registries.NOISE_SETTINGS);
            var holder = settingsLookup.get(NoiseGeneratorSettings.OVERWORLD).orElse(null);
            if (holder == null) {
                return new Probe(DepthAxis.UNKNOWN, false, Double.NaN,
                        "minecraft:overworld noise settings not present in the registry");
            }
            NoiseGeneratorSettings settings = holder.value();
            grandTerrain = OpenOcean.usesGrandTerrain(settings);
            NoiseRouter router = settings.noiseRouter();
            depth = router.depth();
        } catch (Exception e) {
            LOGGER.warn("gtsat: could not obtain the depth density function from the registry", e);
            return new Probe(DepthAxis.UNKNOWN, false, Double.NaN, "registry lookup failed: " + e);
        }

        // Sample the axis across the whole world height.
        double[] sampled = new double[PROBE_YS.length];
        for (int i = 0; i < PROBE_YS.length; i++) {
            sampled[i] = evaluate(depth, PROBE_YS[i]);
        }

        double dMin = evaluate(depth, WORLD_MIN_Y);
        double dM40 = evaluate(depth, -40);
        double dSea = evaluate(depth, 64);
        double dTop = evaluate(depth, WORLD_TOP);
        double dM64 = evaluate(depth, -64);
        double d320 = evaluate(depth, 320);

        // Vanilla's gradient saturates to exactly 1.5 at/below -64 and -1.5 at/above 320.
        boolean vanillaSignature = nearlyEqual(dMin, 1.5)
                && nearlyEqual(dM64, 1.5)
                && nearlyEqual(d320, -1.5)
                && nearlyEqual(dTop, -1.5);

        // grandterrain's piecewise axis: four deliberate anchors, and crucially
        // depth(-64) no longer saturates (it is mid-segment, ~0.3444).
        boolean grandterrainSignature = nearlyEqual(dMin, 1.5)
                && nearlyEqual(dM40, 0.2)
                && nearlyEqual(dSea, 0.0)
                && nearlyEqual(dTop, -1.5)
                && !nearlyEqual(dM64, 1.5);

        DepthAxis axis;
        if (grandterrainSignature) {
            axis = DepthAxis.GRANDTERRAIN_PIECEWISE;
        } else if (vanillaSignature) {
            axis = DepthAxis.VANILLA_CLAMPED_GRADIENT;
        } else {
            axis = DepthAxis.OTHER_DIMENSION_AWARE;
        }

        double atBoundary = evaluate(depth, undergroundMaxY);
        StringBuilder sb = new StringBuilder();
        sb.append("anchors:");
        sb.append(String.format(" d(-256)=%.4f", dMin));
        sb.append(String.format(" d(-40)=%.4f", dM40));
        sb.append(String.format(" d(64)=%.4f", dSea));
        sb.append(String.format(" d(%d)=%.4f", WORLD_TOP, dTop));
        sb.append(String.format("; d(-64)=%.4f (vanilla would saturate to 1.5)", dM64));
        sb.append("; samples:");
        for (int i = 0; i < PROBE_YS.length; i++) {
            sb.append(String.format(" y=%d->%.4f", PROBE_YS[i], sampled[i]));
        }
        return new Probe(axis, grandTerrain, atBoundary, sb.toString());
    }

    private static double evaluate(DensityFunction fn, int y) {
        try {
            return fn.compute(new DensityFunction.FunctionContext() {
                @Override
                public int blockX() {
                    return 0;
                }

                @Override
                public int blockY() {
                    return y;
                }

                @Override
                public int blockZ() {
                    return 0;
                }
            });
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static boolean nearlyEqual(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return false;
        }
        return Math.abs(a - b) < 1e-9;
    }

    /** Vanilla's gradient, reproduced locally for reference numbers in messages. */
    public static double vanillaDepthAt(int y) {
        if (y <= VANILLA_GRADIENT_FROM_Y) {
            return VANILLA_GRADIENT_FROM_VALUE;
        }
        if (y >= VANILLA_GRADIENT_TO_Y) {
            return VANILLA_GRADIENT_TO_VALUE;
        }
        double f = (double) (y - VANILLA_GRADIENT_FROM_Y)
                / (double) (VANILLA_GRADIENT_TO_Y - VANILLA_GRADIENT_FROM_Y);
        return VANILLA_GRADIENT_FROM_VALUE
                + f * (VANILLA_GRADIENT_TO_VALUE - VANILLA_GRADIENT_FROM_VALUE);
    }

    /** The ERROR text when the axis is found to be unfit for this world. */
    public static String misalignmentMessage(int undergroundMaxY, Probe probe) {
        int distinct = 384;
        int actualY = (int) Math.round(-64 + (ClimatePointBuilder.DepthBand.SPLIT_FLOAT - 1.5) / -3.0 * 384);
        return String.format(
                "gtsat: REACHED DEPTH-AXIS CHECK — the surface/underground split is misplaced. "
                        + "grandterrain's noise_router.depth behaves like vanilla's "
                        + "minecraft:overworld/depth: a gradient built for a 384-tall overworld. "
                        + "This world is %d..%d (%d blocks), so the axis is CONSTANT outside "
                        + "y=-64..320 and only %.1f%% of the world is distinguishable. "
                        + "The depth=%.1f boundary therefore lands at y=%d, NOT at the configured "
                        + "underground_max_y=%d (off by %d blocks) — underground biomes would start "
                        + "at y=%d instead of y=%d. Measured: %s. "
                        + "FIX: grandterrain must ship a dimension-aware depth function "
                        + "(e.g. y_clamped_gradient(-256→1.5, %d→0.5) below the split and "
                        + "y_clamped_gradient(%d→0.5, %d→-1.5) above it), so that depth(%d)==%.1f. "
                        + "gtsat is placing the split at the closest representable value and will "
                        + "report this again on every world load. KNOWN LIMITATION until fixed.",
                WORLD_MIN_Y, WORLD_TOP, WORLD_HEIGHT,
                100.0 * distinct / WORLD_HEIGHT,
                ClimatePointBuilder.DepthBand.SPLIT_FLOAT, actualY,
                undergroundMaxY, Math.abs(actualY - undergroundMaxY),
                actualY, undergroundMaxY, probe.detail,
                undergroundMaxY, undergroundMaxY, WORLD_TOP, undergroundMaxY,
                ClimatePointBuilder.DepthBand.SPLIT_FLOAT);
    }
}
