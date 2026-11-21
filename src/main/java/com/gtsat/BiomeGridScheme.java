package com.gtsat;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic 5-D climate lattice used by {@link GtSatBiomeInjector}
 * (mode = inject).  This class is the <b>single source of truth</b> for the
 * layout: {@code scripts/prove_inject_coverage.py} mirrors this exact algorithm
 * so the offline Monte-Carlo proof measures what the game will actually build.
 * See {@code INJECT_SCHEME.md}.
 *
 * <h2>The idea in one paragraph</h2>
 * Vanilla's {@code multi_noise} biome source partitions the float climate box
 * {@code [-1,1]^5} (temperature, humidity, continentalness, erosion, weirdness)
 * into axis-aligned ranges.  We lay our own lattice over that box: each of the
 * five axes is cut into a fixed number of equal-width slices, giving a grid of
 * {@code G = nt*nh*nc*ne*nw} cells (mixed-radix).  We walk that grid in
 * lexicographic order and hand each biome a contiguous run of cells.  Because
 * the runs are contiguous, disjoint and their union is the whole grid, the
 * cells of different biomes never overlap and every biome owns at least one
 * non-empty cell — provided {@code G >= biomeCount}.  That is the whole
 * coverage argument; the Monte-Carlo script checks it empirically.
 *
 * <h2>biome_size_ratio</h2>
 * The ratio controls the density of the grid along the two "spatial" axes that
 * dominate perceived biome patch size (temperature &amp; humidity — the two the
 * vanilla climate lookup resolves coarsest).  A larger ratio multiplies the
 * number of cells on those axes, i.e. fewer biomes per cell → larger, more
 * continuous biome patches (the ratio also multiplies the per-biome run length,
 * so a biome spans a wider arc of the box).  A ratio &lt; 1 shrinks the grid so a
 * biome's run occupies a smaller, choppier arc.  The total cell count is kept
 * {@code >= biomeCount} by clamping, so coverage never depends on the ratio.
 */
public final class BiomeGridScheme {
    /**
     * The climate value domain.  Vanilla quantises float coordinates with
     * {@code Math.round(x * 10000)} and the legal range is {@code [-10000, 10000]}
     * (both inclusive).  Represented here in "units" so the same integers are
     * used by Java ({@link net.minecraft.world.level.biome.Climate#quantizeCoord})
     * and by the Python mirror.
     */
    public static final int PARAM_MIN = -10000;
    public static final int PARAM_MAX = 10000;

    /** A single axis-aligned cell in quantised climate units. */
    public record Cell(int tMin, int tMax, int hMin, int hMax, int cMin, int cMax,
                       int eMin, int eMax, int wMin, int wMax) {
    }

    /** Result of a layout: per-biome contiguous cell list plus grid metadata. */
    public record Layout(int nt, int nh, int nc, int ne, int nw, int total,
                         List<List<Cell>> perBiome) {
        public Cell cellForBiome(int biomeIndex, int cellWithinBiome) {
            List<Cell> cells = perBiome.get(biomeIndex);
            return cells.get(Math.floorMod(cellWithinBiome, cells.size()));
        }
    }

    /** Hard cap so the cell count stays a sane integer for 393 biomes. */
    private static final int HARD_MAX_CELLS = 1 << 20;

    private BiomeGridScheme() {
    }

    /**
     * Build the lattice for {@code biomeCount} biomes.
     *
     * <p>The axis bin counts are derived deterministically from the biome count
     * and {@code biomeSizeRatio}; the algorithm is reproduced 1:1 in Python.
     * The five counts form a mixed-radix grid walked so temperature varies
     * slowest (widest slabs) and weirdness fastest (finest subdivision), which
     * keeps neighbouring biomes climate-close in the coarse axes and spreads
     * the fine subdivisions across weirdness.</p>
     */
    public static Layout build(int biomeCount, double biomeSizeRatio) {
        if (biomeCount <= 0) {
            throw new IllegalArgumentException("gtsat: biomeCount must be > 0, got " + biomeCount);
        }
        final double ratio = biomeSizeRatio > 0 ? biomeSizeRatio : 1.0;

        // --- pick axis bin counts: 5 roughly-balanced integers -------------
        // base = smallest integer b with b^5 >= N (integer-only, so Python
        // mirror is bit-exact; no float rounding at power boundaries).
        int base = 1;
        while ((long) base * base * base * base * base < biomeCount) {
            base++;
        }
        int scaled = (int) Math.round(base * ratio);
        if (scaled < 1) {
            scaled = 1;
        }
        // temperature & humidity carry the ratio; the other three stay at base.
        int nt = scaled;
        int nh = scaled;
        int nc = base;
        int ne = base;
        int nw = base;

        // --- guarantee total cells >= biomeCount (coverage precondition) ---
        long total = (long) nt * nh * nc * ne * nw;
        while (total < biomeCount && total < HARD_MAX_CELLS) {
            // grow the axis with the smallest count first to stay cube-ish
            int min = Math.min(Math.min(nt, nh), Math.min(nc, Math.min(ne, nw)));
            if (nt == min) {
                nt++;
            } else if (nh == min) {
                nh++;
            } else if (nc == min) {
                nc++;
            } else if (ne == min) {
                ne++;
            } else {
                nw++;
            }
            total = (long) nt * nh * nc * ne * nw;
        }
        final int gridTotal = (int) total;

        // --- walk cells in lexicographic order and slice them to biomes ----
        // Each biome gets a contiguous run; the last biome absorbs the
        // remainder so the runs are an exact, gap-free partition of [0,gridTotal).
        List<List<Cell>> perBiome = new ArrayList<>(biomeCount);
        int cursor = 0;
        for (int b = 0; b < biomeCount; b++) {
            int remainingBiomes = biomeCount - b;
            int remainingCells = gridTotal - cursor;
            int take = remainingCells / remainingBiomes;      // >=1 because gridTotal>=biomeCount
            if (take < 1) {
                take = 1;
            }
            List<Cell> cells = new ArrayList<>(take);
            for (int k = 0; k < take; k++) {
                int flat = cursor + k;
                cells.add(cellAt(flat, nt, nh, nc, ne, nw));
            }
            cursor += take;
            perBiome.add(cells);
        }
        return new Layout(nt, nh, nc, ne, nw, gridTotal, List.copyOf(perBiome));
    }

    /**
     * Decode a flat mixed-radix index into one axis-aligned {@link Cell}.
     *
     * <p>Radix order: temp slowest (outermost), then humidity, continentalness,
     * erosion, weirdness fastest (innermost).  Each axis is cut into n equal
     * slices spanning {@code [PARAM_MIN, PARAM_MAX]}; slice {@code i} covers
     * {@code [PARAM_MIN + floor(i*span/n), PARAM_MIN + floor((i+1)*span/n) - 1]}
     * in quantised units (clamped to PARAM_MAX).  Adjacent slices share no
     * point, so the whole grid is a partition of the box.</p>
     */
    static Cell cellAt(int flat, int nt, int nh, int nc, int ne, int nw) {
        int w = flat % nw;           flat /= nw;
        int e = flat % ne;           flat /= ne;
        int c = flat % nc;           flat /= nc;
        int h = flat % nh;           flat /= nh;
        int t = flat % nt;
        int[] tR = slice(t, nt);
        int[] hR = slice(h, nh);
        int[] cR = slice(c, nc);
        int[] eR = slice(e, ne);
        int[] wR = slice(w, nw);
        return new Cell(tR[0], tR[1], hR[0], hR[1], cR[0], cR[1], eR[0], eR[1], wR[0], wR[1]);
    }

    /** Inclusive quantised-integer range for slice {@code i} of {@code n} over the domain. */
    static int[] slice(int i, int n) {
        long span = (long) PARAM_MAX - PARAM_MIN;      // 20000
        int lo = (int) (PARAM_MIN + Math.floorDiv(i * span, n));
        int hi = (i == n - 1)
                ? PARAM_MAX
                : (int) (PARAM_MIN + Math.floorDiv((i + 1L) * span, n) - 1);
        return new int[]{lo, hi};
    }
}
