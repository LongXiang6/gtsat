package com.gtsat;

import com.grandterrain.worldgen.cave.UndergroundCavities;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the per-dimension, per-height-band biome cohorts (spec §1.1 v1.1).
 *
 * <p>Dimension attribution runs in the order the spec mandates:</p>
 * <ol start="0">
 *   <li>exclusion table (literal biome ids and whole namespaces),</li>
 *   <li>namespace override table ({@code aether → EXCLUDE}, {@code betterend → end}, ...),</li>
 *   <li>dimension tags ({@code #minecraft:is_nether} / {@code #c:is_nether}, ...),</li>
 *   <li>name keyword fallback, only for biomes that carry no dimension tag.</li>
 * </ol>
 *
 * <p>Everything here is deliberately deterministic: the lists are sorted by
 * {@code ResourceLocation.toString()} (spec §2.6) before anything else happens,
 * so two machines with the same biome set get byte-identical cohorts even when
 * the registry iterates in a different order.</p>
 */
public final class CohortBuilder {
    public static final String OVERWORLD = "overworld";
    public static final String NETHER = "nether";
    public static final String END = "end";

    /** Spec §1.1 step 3 nether keywords.  {@code void} and {@code grove} were removed in v1.1. */
    private static final Pattern NETHER_WORDS = Pattern.compile(
            "(?:^|[/_.-])(?:nether|warped|crimson|basalt|soul|blackstone|quartz|magma)(?:$|[/_.-])");

    /** Spec §1.1 step 3 end keywords (note: {@code void} is intentionally gone). */
    private static final Pattern END_WORDS = Pattern.compile(
            "(?:^|[/_.-])(?:end|chorus|purpur|shadow)(?:$|[/_.-])");

    /**
     * Immutable cohort snapshot for one biome source.
     *
     * @param all                 every dispatchable biome (exclusions already removed), name-sorted
     * @param dimension           every biome that belongs to the requested dimension, name-sorted
     * @param surface             overworld only: the surface band (empty for nether/end)
     * @param underground         overworld only: the underground band (empty for nether/end)
     * @param heuristicClassified every biome that carried no tag and was therefore classified by
     *                            name in step 3; never silently dropped (spec A9)
     * @param overridden          every biome whose dimension came from the namespace override table
     * @param excluded            every biome removed by the exclusion table (step 0 / {@code EXCLUDE})
     */
    public record Cohorts(
            List<Holder<Biome>> all,
            List<Holder<Biome>> dimension,
            List<Holder<Biome>> surface,
            List<Holder<Biome>> underground,
            List<ResourceLocation> heuristicClassified,
            List<ResourceLocation> overridden,
            List<ResourceLocation> excluded
    ) {
    }

    private CohortBuilder() {
    }

    /**
     * Spec §1.1 + §1.2: classify every registered biome, keep the ones that
     * belong to {@code dimension}, and split the overworld into two bands.
     *
     * @throws IllegalStateException when the surface/underground partition is not
     *                               an exact complement of the dimension cohort
     */
    public static Cohorts build(HolderLookup.RegistryLookup<Biome> lookup, String dimension) {
        return build(lookup, dimension, GtSatConfig.namespaceOverrides(),
                GtSatConfig.excludedBiomes(), GtSatConfig.excludedNamespaces());
    }

    /** Uses the immutable view read for this inject callback, including explicit empty lists. */
    public static Cohorts build(HolderLookup.RegistryLookup<Biome> lookup, String dimension,
                                BiomeConfigView cfg) {
        return build(lookup, dimension, cfg.namespaceOverrides,
                Set.copyOf(cfg.excludeBiomes), Set.copyOf(cfg.excludeNamespaces));
    }

    private static Cohorts build(HolderLookup.RegistryLookup<Biome> lookup, String dimension,
                                 Map<String, String> overrides, Set<String> excludedBiomeIds,
                                 Set<String> excludedNamespaces) {
        List<Holder<Biome>> registered = lookup.listElements()
                .map(holder -> (Holder<Biome>) holder)
                .sorted(Comparator.comparing(CohortBuilder::biomeName))
                .toList();

        List<Holder<Biome>> dispatchable = new ArrayList<>();
        List<Holder<Biome>> forDimension = new ArrayList<>();
        List<ResourceLocation> heuristic = new ArrayList<>();
        List<ResourceLocation> overridden = new ArrayList<>();
        List<ResourceLocation> excluded = new ArrayList<>();

        for (Holder<Biome> holder : registered) {
            ResourceLocation id = biomeId(holder);

            // ---- step 0: exclusion table -------------------------------------
            if (excludedBiomeIds.contains(id.toString()) || excludedNamespaces.contains(id.getNamespace())) {
                excluded.add(id);
                continue;
            }

            // ---- step 1: namespace override table ----------------------------
            String target = overrides.get(id.getNamespace());
            if (target != null) {
                if (GtSatConfig.EXCLUDE.equals(target)) {
                    excluded.add(id);
                    continue;
                }
                dispatchable.add(holder);
                overridden.add(id);
                if (target.equals(dimension)) {
                    forDimension.add(holder);
                }
                continue;
            }

            // ---- step 2 + 3: dimension tags, then name keywords ---------------
            dispatchable.add(holder);
            if (classifyByTagOrName(holder, heuristic).equals(dimension)) {
                forDimension.add(holder);
            }
        }

        List<Holder<Biome>> surface = List.of();
        List<Holder<Biome>> underground = List.of();
        if (OVERWORLD.equals(dimension)) {
            Set<String> undergroundWhitelist = GtSatConfig.undergroundBiomes();
            List<Holder<Biome>> undergroundBand = new ArrayList<>();
            Set<ResourceLocation> undergroundIds = new HashSet<>();
            for (Holder<Biome> holder : forDimension) {
                if (isUnderground(holder, undergroundWhitelist)) {
                    undergroundBand.add(holder);
                    undergroundIds.add(biomeId(holder));
                }
            }
            // Spec §1.2: surface is defined by subtraction — "the whole dimension
            // minus the underground band" — never by a second, independently
            // written condition.  That way a missed biome is impossible by
            // construction, and the assertion below only has to catch programming
            // errors in the band test itself.
            List<Holder<Biome>> surfaceBand = new ArrayList<>();
            for (Holder<Biome> holder : forDimension) {
                if (!undergroundIds.contains(biomeId(holder))) {
                    surfaceBand.add(holder);
                }
            }
            // Spec §1.2 hard constraint: the two bands must be complementary and
            // their union must be the whole dimension.  A missed biome here would
            // mean "a biome that can never generate", which is 需求 2.
            assertComplement(dimension, forDimension, surfaceBand, undergroundBand);
            surface = List.copyOf(surfaceBand);
            underground = List.copyOf(undergroundBand);
        }

        return new Cohorts(
                List.copyOf(dispatchable),
                List.copyOf(forDimension),
                surface,
                underground,
                List.copyOf(heuristic),
                List.copyOf(overridden),
                List.copyOf(excluded)
        );
    }

    /**
     * Spec §1.1 step 2 (dimension tags, evaluated in the given order) and step 3
     * (name keywords, only when no dimension tag matched).  Every biome that
     * reaches step 3 is recorded in {@code heuristic} so it can be reported
     * instead of silently disappearing.
     */
    private static String classifyByTagOrName(Holder<Biome> holder, List<ResourceLocation> heuristic) {
        if (holder.is(BiomeTags.IS_NETHER) || holder.is(Tags.Biomes.IS_NETHER)) {
            return NETHER;
        }
        if (holder.is(BiomeTags.IS_END) || holder.is(Tags.Biomes.IS_END)) {
            return END;
        }
        if (holder.is(BiomeTags.IS_OVERWORLD)) {
            return OVERWORLD;
        }
        String resolved;
        String path = biomeId(holder).getPath();
        if (NETHER_WORDS.matcher(path).find()) {
            resolved = NETHER;
        } else if (END_WORDS.matcher(path).find()) {
            resolved = END;
        } else {
            resolved = OVERWORLD;
        }
        heuristic.add(biomeId(holder));
        return resolved;
    }

    /**
     * Spec §1.2 underground test — four paths, any one is enough:
     * the cave tags, the {@code deep_dark} special case, the keywords, and the
     * explicit id whitelist.  Note that there is deliberately <b>no</b> {@code deep}
     * or {@code deeps} keyword: {@code deep_ocean} and friends are surface biomes.
     */
    private static boolean isUnderground(Holder<Biome> holder, Set<String> whitelist) {
        return !SpatialBiomeLayout.isOcean(holder)
                && (UndergroundCavities.isCavernBiome(holder) || whitelist.contains(biomeName(holder)));
    }

    /**
     * Fails loudly instead of silently losing a biome.
     *
     * <p>Invariants checked: no biome lost, no biome duplicated, and the union of
     * the two bands equals the dimension cohort.</p>
     */
    private static void assertComplement(
            String dimension,
            List<Holder<Biome>> dimensionCohort,
            List<Holder<Biome>> surface,
            List<Holder<Biome>> underground
    ) {
        int total = surface.size() + underground.size();
        if (total != dimensionCohort.size()) {
            throw new IllegalStateException("gtsat: surface(" + surface.size() + ") + underground("
                    + underground.size() + ") != " + dimension + " cohort(" + dimensionCohort.size()
                    + "); a biome would be unreachable, which violates the full-coverage requirement");
        }
        Set<ResourceLocation> expected = new HashSet<>();
        for (Holder<Biome> holder : dimensionCohort) {
            expected.add(biomeId(holder));
        }
        Set<ResourceLocation> union = new HashSet<>();
        for (Holder<Biome> holder : surface) {
            union.add(biomeId(holder));
        }
        for (Holder<Biome> holder : underground) {
            union.add(biomeId(holder));
        }
        if (union.size() != total) {
            throw new IllegalStateException("gtsat: " + dimension
                    + " surface/underground bands overlap; a biome would appear in both height bands");
        }
        if (!union.equals(expected)) {
            Set<ResourceLocation> missing = new HashSet<>(expected);
            missing.removeAll(union);
            throw new IllegalStateException("gtsat: " + dimension
                    + " surface/underground union != dimension cohort, missing=" + missing
                    + "; a biome would be unreachable, which violates the full-coverage requirement");
        }
    }

    /** Spec §2.6: the cohort order is the dictionary order of {@code ResourceLocation.toString()}. */
    static String biomeName(Holder<Biome> holder) {
        return holder.unwrapKey().map(key -> key.location().toString()).orElse("unbound");
    }

    static ResourceLocation biomeId(Holder<Biome> holder) {
        return holder.unwrapKey()
                .map(key -> key.location())
                .orElse(ResourceLocation.fromNamespaceAndPath("minecraft", "unknown"));
    }
}
