package com.gtsat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Deterministic slot-based biome source (spec §2).
 *
 * <p>Every (x, z) maps to one region plus one sub-cell, the region index is
 * hashed to a traversal counter {@code n}, and {@code n} selects a slot inside
 * the dimension's cohort.  Because a full cycle walks a contiguous range of
 * slot numbers of length {@code CYCLE * CELLS_PER_REGION >= N}, every biome of
 * the cohort is guaranteed to appear inside a cycle (spec §2.4).</p>
 *
 * <p>NeoForge 1.21.1 port notes: {@code RegistryOps.retrieveRegistryLookup} no
 * longer exists; the 1.21.1 equivalent is {@code RegistryOps.retrieveGetter},
 * which hands back a {@link HolderGetter}.  Every vanilla and NeoForge
 * deserialisation path builds that getter from a
 * {@link HolderLookup.RegistryLookup}, so the source upgrades it back to a
 * lookup to enumerate the registry, and falls back to the live server's
 * registry access if a future loader path ever supplies a plain getter.  The
 * registry is read lazily, so decoding a preset never needs a running server.</p>
 */
public final class SlotBiomeSource extends BiomeSource {
    /** Quart → block conversion.  Spec §2.2's {@code x}/{@code z} are block coordinates. */
    public static final int QUARTER_BLOCK = 4;

    private static final Logger LOGGER = LoggerFactory.getLogger("gtsat");

    public static final MapCodec<SlotBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    RegistryOps.<Biome, SlotBiomeSource>retrieveGetter(Registries.BIOME),
                    Codec.STRING.optionalFieldOf("dimension", "overworld")
                            .forGetter(source -> source.dimension),
                    Codec.LONG.optionalFieldOf("salt", 0L)
                            .forGetter(source -> source.salt),
                    Codec.INT.optionalFieldOf("region_chunks", GtSatConfig.DEFAULT_REGION_CHUNKS)
                            .forGetter(source -> source.regionChunks),
                    Codec.INT.optionalFieldOf("cells_per_region", GtSatConfig.DEFAULT_CELLS_PER_REGION)
                            .forGetter(source -> source.cellsPerRegion),
                    Codec.INT.optionalFieldOf("underground_max_y", GtSatConfig.DEFAULT_UNDERGROUND_MAX_Y)
                            .forGetter(source -> source.undergroundMaxY)
            ).apply(instance, SlotBiomeSource::new));

    private final HolderGetter<Biome> biomeGetter;
    private final String dimension;
    private final long salt;
    private final int regionChunks;
    private final int cellsPerRegion;
    private final int undergroundMaxY;

    /** Spec §2.1 {@code REGION_BLOCKS = REGION_CHUNKS * 16}. */
    private final int regionBlocks;
    /** Spec §2.1 {@code GRID = ceil(sqrt(CELLS_PER_REGION))}. */
    private final int grid;
    /** Spec §2.1 {@code SHIFT_STRIDE} (default = {@code CELLS_PER_REGION}). */
    private final int shiftStride;

    private volatile List<Holder<Biome>> allBiomes;
    private volatile List<Holder<Biome>> dimensionBiomes;
    private volatile List<Holder<Biome>> surfaceBiomes;
    private volatile List<Holder<Biome>> undergroundBiomes;

    private SlotBiomeSource(
            HolderGetter<Biome> biomeGetter,
            String dimension,
            long salt,
            int regionChunks,
            int cellsPerRegion,
            int undergroundMaxY
    ) {
        super();
        if (regionChunks < 1) {
            throw new IllegalArgumentException("gtsat: region_chunks must be >= 1, got " + regionChunks);
        }
        if (cellsPerRegion < 1) {
            throw new IllegalArgumentException("gtsat: cells_per_region must be >= 1, got " + cellsPerRegion);
        }
        this.biomeGetter = biomeGetter;
        this.dimension = dimension.toLowerCase(Locale.ROOT);
        this.salt = salt;
        this.regionChunks = regionChunks;
        this.cellsPerRegion = cellsPerRegion;
        this.undergroundMaxY = undergroundMaxY;
        this.regionBlocks = regionChunks * GtSatConfig.BLOCKS_PER_CHUNK;
        this.grid = (int) Math.ceil(Math.sqrt(cellsPerRegion));
        this.shiftStride = cellsPerRegion * GtSatConfig.SHIFT_STRIDE_PER_CELL;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        resolveBiomes();
        return dimensionBiomes.stream();
    }

    /**
     * Spec §2.2 → §2.4, verbatim.  All arithmetic is integer / unsigned so the
     * result is reproducible on every machine and symmetric for negative
     * coordinates.
     */
    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        resolveBiomes();

        List<Holder<Biome>> cohort = cohortForHeight(quartY * QUARTER_BLOCK);
        if (cohort.isEmpty()) {
            // Spec §1.3: an empty cohort falls back to the whole dimension ...
            cohort = dimensionBiomes.isEmpty() ? allBiomes : dimensionBiomes;
        }
        if (cohort.isEmpty()) {
            // ... and an empty dimension is a hard error, never a silent fallback.
            throw new IllegalStateException("No registered biomes are available for " + dimension);
        }

        final int x = quartX * QUARTER_BLOCK;
        final int z = quartZ * QUARTER_BLOCK;

        // --- spec §2.2: coordinate → slot -------------------------------------
        final int rx = Math.floorDiv(x, regionBlocks);
        final int rz = Math.floorDiv(z, regionBlocks);
        final int lx = Math.floorMod(x, regionBlocks);
        final int lz = Math.floorMod(z, regionBlocks);
        final int cellX = Math.min(grid - 1, lx * grid / regionBlocks);
        final int cellZ = Math.min(grid - 1, lz * grid / regionBlocks);
        final int cell = cellZ * grid + cellX;
        final int j = Math.min(cell, cellsPerRegion - 1);

        // --- spec §2.3: region → traversal index n ---------------------------
        final long n = mix(((long) rx << 32) ^ (rz & 0xFFFFFFFFL) ^ salt);

        // --- spec §2.4: slot → biome (this is what guarantees coverage) ------
        final int n1 = cohort.size();
        final long cycle = (n1 + cellsPerRegion - 1) / cellsPerRegion;   // ceil(N / CELLS_PER_REGION)
        final long u = Long.remainderUnsigned(n, cycle);
        final long g = Long.divideUnsigned(n, cycle);
        final long s = u * cellsPerRegion + j;
        final long shift = g * shiftStride;
        final int idx = (int) Math.floorMod(s + shift, n1);
        return cohort.get(idx);
    }

    /** Spec §1.2: only the overworld is split into height bands. */
    private List<Holder<Biome>> cohortForHeight(int y) {
        if (CohortBuilder.OVERWORLD.equals(dimension)) {
            return y <= undergroundMaxY ? undergroundBiomes : surfaceBiomes;
        }
        return dimensionBiomes;
    }

    /**
     * Fills the biome cohorts from the live biome registry.  Kept lazy on
     * purpose: the codec is decoded while a data pack loads, which may be before
     * any server or registry access object exists.
     */
    private void resolveBiomes() {
        if (this.allBiomes != null) {
            return;
        }
        synchronized (this) {
            if (this.allBiomes != null) {
                return;
            }
            HolderLookup.RegistryLookup<Biome> lookup = resolveLookup();
            LOGGER.info("gtsat: classification tables: namespaceOverrides={} excludedBiomes={} excludedNamespaces={}"
                            + " undergroundWhitelist={}",
                    GtSatConfig.namespaceOverrides(), GtSatConfig.excludedBiomes(),
                    GtSatConfig.excludedNamespaces(), GtSatConfig.undergroundBiomes());
            CohortBuilder.Cohorts cohorts = CohortBuilder.build(lookup, this.dimension);
            List<Holder<Biome>> all = cohorts.all();
            List<Holder<Biome>> forDimension = cohorts.dimension();
            final List<Holder<Biome>> surface;
            final List<Holder<Biome>> underground;
            if (CohortBuilder.OVERWORLD.equals(this.dimension)) {
                surface = cohorts.surface();
                underground = cohorts.underground();
            } else {
                // Spec §1.2: the nether and the end each have a single cohort.
                surface = forDimension;
                underground = List.of();
            }

            LOGGER.info("gtsat: {} cohort: {} biomes (surface={}, underground={}), {} dispatchable of {} registered",
                    this.dimension, forDimension.size(), surface.size(), underground.size(),
                    all.size(), all.size() + cohorts.excluded().size());
            // Spec §1.1 / A9: nothing may be dropped or classified silently.
            LOGGER.info("gtsat: {} local salt={} regionChunks={} cellsPerRegion={} grid={} regionBlocks={}"
                            + " undergroundMaxY={} cycle(own cohort)={}",
                    this.dimension, salt, regionChunks, cellsPerRegion,
                    (int) Math.ceil(Math.sqrt(cellsPerRegion)), regionChunks * GtSatConfig.BLOCKS_PER_CHUNK,
                    undergroundMaxY,
                    forDimension.isEmpty() ? 0 : (forDimension.size() + cellsPerRegion - 1) / cellsPerRegion);
            logIdList("excluded by the exclusion table", cohorts.excluded());
            logIdList("resolved by the namespace override table", cohorts.overridden());
            logIdList("classified by name heuristic", cohorts.heuristicClassified());

            this.undergroundBiomes = underground;
            this.surfaceBiomes = surface;
            this.dimensionBiomes = forDimension;
            this.allBiomes = all;
        }
    }

    /** Spec §1.1 / A9: every excluded or heuristically-classified biome is printed, never silently dropped. */
    private void logIdList(String what, List<ResourceLocation> ids) {
        if (ids.isEmpty()) {
            LOGGER.info("gtsat: no biome {}", what);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (ResourceLocation id : ids) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(id);
        }
        LOGGER.info("gtsat: {} biome(s) {}: {}", ids.size(), what, sb);
    }

    private HolderLookup.RegistryLookup<Biome> resolveLookup() {
        if (this.biomeGetter instanceof HolderLookup.RegistryLookup<Biome> lookup) {
            return lookup;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return server.registryAccess().lookupOrThrow(Registries.BIOME);
        }
        throw new IllegalStateException(
                "gtsat biome source requires registry access for " + Registries.BIOME.location());
    }

    /**
     * splitmix64 finaliser, spec §2.3 verbatim.
     *
     * <p>It is used to spread neighbouring regions across the traversal
     * sequence so walking along the x axis does not reveal biomes in
     * alphabetical order.</p>
     */
    static long mix(long a) {
        a += 0x9E3779B97F4A7C15L;
        a = (a ^ (a >>> 30)) * 0xBF58476D1CE4E5B9L;
        a = (a ^ (a >>> 27)) * 0x94D049BB133111EBL;
        return a ^ (a >>> 31);
    }

    @Override
    public String toString() {
        return "SlotBiomeSource[" + dimension + ", salt=" + salt
                + ", regionChunks=" + regionChunks
                + ", cellsPerRegion=" + cellsPerRegion
                + ", undergroundMaxY=" + undergroundMaxY + "]";
    }
}
