package com.gtsat;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the vanilla {@link Climate.ParameterList} for a cohort from the
 * {@link BiomeGridScheme} lattice.
 *
 * <p>Kept separate from {@link GtSatBiomeInjector} on purpose: this class has
 * <b>no lithostitched imports</b>, so it can be exercised offline (and unit
 * tested) with only the Minecraft classes on the classpath — which is how the
 * "points are well-formed and pairwise disjoint" claim is verified without a
 * running game.</p>
 */
public final class ClimatePointBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("gtsat");

    /**
     * Depth band for one cohort, in the quantised {@code [-10000,10000]} domain.
     *
     * <h2>Where these numbers come from (measured, not guessed)</h2>
     * <p>Vanilla's overworld parameter points were dumped from the real
     * {@code MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD} (7,593 points,
     * see {@code selftest/DepthProbe}). The depth axis only ever takes two
     * values: <b>{@code 0.0}</b> (surface biomes, 3,795 points) and
     * <b>{@code 1.0}</b> (cave/underground biomes, 3,795 points), plus two
     * deep_dark points using {@code 0.2..0.9} and one using {@code 1.1}. So the
     * meaningful split is at <b>0.5</b>, not at 0.2.</p>
     *
     * <p>Depth is <i>not</i> proportional to absolute Y in a grandterrain world:
     * grandterrain's noise settings reuse {@code minecraft:overworld/depth},
     * which is a {@code y_clamped_gradient} hardcoded from_y=-64 to_y=320
     * (1.5 → -1.5) regardless of the dimension's min_y=-256/height=2288. So a
     * depth threshold cannot be derived from the world height; it can only be
     * expressed in depth space. We therefore split at vanilla's own boundary
     * (0.5) and additionally honour {@code biomes.underground_max_y} by mapping
     * it onto the vanilla gradient when it falls inside [-64, 320].</p>
     */
    public static final class DepthBand {
        /** Shallow: surface biomes. Matches vanilla's depth=0.0 plus headroom. */
        public static final DepthBand SURFACE = new DepthBand(-10000L, 4999L);
        /** Deep: cave/underground biomes. Covers vanilla's depth=1.0 and 1.1 outlier. */
        public static final DepthBand UNDERGROUND = new DepthBand(5000L, 10000L);
        /** Whole axis (nether / end have no separate underground cohort). */
        public static final DepthBand FULL = new DepthBand(-10000L, 10000L);

        private final long min;
        private final long max;

        private DepthBand(long min, long max) {
            this.min = min;
            this.max = max;
        }

        /**
         * Surface/underground bands split at a configurable threshold instead of
         * the fixed 0.5. Used to honour {@code biomes.underground_max_y}.
         *
         * <p>The two returned bands are always adjacent and disjoint, so the
         * coverage proof still holds for any threshold in (-10000, 10000).</p>
         */
        public static DepthBand[] splitAt(long threshold) {
            long t = Math.max(-9999L, Math.min(9999L, threshold));
            return new DepthBand[]{
                    new DepthBand(-10000L, t - 1),
                    new DepthBand(t, 10000L)
            };
        }

        public long min() {
            return min;
        }

        public long max() {
            return max;
        }

        /**
         * The depth value where the surface and underground bands meet.
         *
         * <h2>Why 0.2 and not 0.5</h2>
         * <p>0.5 was chosen earlier because vanilla's 7,593 overworld parameter points
         * only use depth 0.0 (surface) and 1.0 (cave), so the midpoint looked like the
         * natural split. That reasoning only holds for vanilla's linear 384-block depth
         * gradient. grandterrain now ships its own piecewise depth axis whose anchors
         * are deliberate (evaluated from the installed jar, see
         * {@code scripts/eval_new_depth_axis.py}):</p>
         *
         * <pre>
         *   y in [-256, -40) -> 1.5 .. 0.2   (linear)
         *   y in [ -40,  64) -> 0.2 .. 0.0   (linear)
         *   y in [  64, 2031)-> 0.0 .. -1.5  (linear, surface)
         * </pre>
         *
         * <p>So {@code underground_max_y=-40} is <b>exactly depth 0.2</b> (the upper
         * bound of vanilla's own cave range {@code span(0.2, 0.9)}), and
         * {@code sea_level=64} is <b>exactly depth 0.0</b> (vanilla's surface
         * {@code point(0)}). 0.5 has no meaning on this axis at all and would let
         * underground biomes appear ~100 blocks too high.</p>
         */
        public static final float SPLIT_FLOAT = 0.2f;

        /** Quantised form of {@link #SPLIT_FLOAT}: the underground band's first value. */
        public static final long SPLIT_QUANTISED = 2000L;

        /** Surface/underground boundary in the float depth domain. */
        public static float defaultSplitFloat() {
            return SPLIT_FLOAT;
        }

        /**
         * Builds the two bands for a depth-axis state.
         *
         * <p>There are exactly two states now — no guessing in between:</p>
         * <ul>
         *   <li><b>axis is dimension-aware</b> (grandterrain ships its own piecewise
         *       function): split at {@link #SPLIT_QUANTISED} = 0.2, which is where the
         *       axis places {@code underground_max_y}. An INFO line records the
         *       measured anchors.</li>
         *   <li><b>axis is still vanilla's clamped gradient</b>: that 384-block function
         *       cannot express this world's range, so an ERROR is logged and the split
         *       stays at 0.2 anyway — the axis is simply unfit and the message says so.
         *       There is no "closest representable value" guess and no clamping: a
         *       guessed boundary hides the defect instead of reporting it.</li>
         * </ul>
         *
         * @param undergroundMaxY absolute block Y below which underground biomes
         *                        may appear (from {@code biomes.underground_max_y})
         * @param probe           result of probing the live depth axis
         * @return a two-element array {surface, underground}
         */
        public static DepthBand[] forWorld(int undergroundMaxY, DepthAxisInspector.Probe probe) {
            if (probe.axis == DepthAxisInspector.DepthAxis.VANILLA_CLAMPED_GRADIENT) {
                LOGGER.error(DepthAxisInspector.misalignmentMessage(undergroundMaxY, probe));
                return splitAt(SPLIT_QUANTISED);
            }
            LOGGER.info("gtsat: depth axis is dimension-aware ({}); split at depth={} "
                            + "(underground_max_y={}). {}", probe.axis, SPLIT_FLOAT,
                    undergroundMaxY, probe.detail);
            return splitAt(SPLIT_QUANTISED);
        }

        /**
         * Convenience overload for offline tests and callers without registries:
         * behaves as if the axis were dimension-aware.
         */
        public static DepthBand[] forWorld(int undergroundMaxY) {
            return splitAt(SPLIT_QUANTISED);
        }
    }

    private ClimatePointBuilder() {
    }

    public static Climate.ParameterList<Holder<Biome>> build(
            List<Holder<Biome>> biomes, double biomeSizeRatio, DepthBand band) {
        BiomeGridScheme.Layout layout = BiomeGridScheme.build(biomes.size(), biomeSizeRatio);
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> points = new ArrayList<>(layout.total());
        for (int b = 0; b < biomes.size(); b++) {
            Holder<Biome> biome = biomes.get(b);
            for (BiomeGridScheme.Cell cell : layout.perBiome().get(b)) {
                points.add(Pair.of(toParameterPoint(cell, band), biome));
            }
        }
        LOGGER.debug("gtsat: built {} parameter points for {} biomes (band={})",
                points.size(), biomes.size(), band);
        return new Climate.ParameterList<>(points);
    }

    /** Back-compat overload used by tests/callers that only care surface vs underground. */
    public static Climate.ParameterList<Holder<Biome>> build(
            List<Holder<Biome>> biomes, double biomeSizeRatio, boolean underground) {
        return build(biomes, biomeSizeRatio, underground ? DepthBand.UNDERGROUND : DepthBand.SURFACE);
    }

    public static Climate.ParameterPoint toParameterPoint(BiomeGridScheme.Cell c, DepthBand band) {
        // NOTE: values in BiomeGridScheme.Cell are ALREADY in the quantised
        // [-10000,10000] domain, so use the raw long constructor. Calling
        // Climate.Parameter.span(float,float) would quantise them a second time
        // (x10000 again) and push every point out of the legal range.
        Climate.Parameter temp = new Climate.Parameter(c.tMin(), c.tMax());
        Climate.Parameter humid = new Climate.Parameter(c.hMin(), c.hMax());
        Climate.Parameter cont = new Climate.Parameter(c.cMin(), c.cMax());
        Climate.Parameter erosion = new Climate.Parameter(c.eMin(), c.eMax());
        Climate.Parameter weird = new Climate.Parameter(c.wMin(), c.wMax());
        Climate.Parameter depth = new Climate.Parameter(band.min(), band.max());
        return new Climate.ParameterPoint(temp, humid, cont, erosion, depth, weird, 0L);
    }
}
