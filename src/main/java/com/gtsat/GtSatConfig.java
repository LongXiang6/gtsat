package com.gtsat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default values and configuration tables for the slot dispatcher
 * (spec §1.1, §1.2, §2.1).
 *
 * <p>The numeric values are only <em>fallbacks</em>: the world preset JSON may
 * override {@code region_chunks}, {@code cells_per_region} and
 * {@code underground_max_y} per dimension, because the right trade-off between
 * patch size and "distance to collect every biome" is a user choice.</p>
 *
 * <p>The classification tables (exclusion list, namespace overrides) are
 * <b>data, not code</b>: spec §1.1 requires them to be configurable instead of
 * being baked into an if/else chain.  The defaults below are exactly the table
 * in spec §1.1; callers may replace rows at runtime.</p>
 */
public final class GtSatConfig {
    /** Spec §2.1 {@code REGION_CHUNKS} default 8 → region edge = 8 * 16 = 128 blocks. */
    public static final int DEFAULT_REGION_CHUNKS = 8;

    /**
     * Spec §2.1 {@code CELLS_PER_REGION} default 4 (2×2 sub-cells).
     *
     * <p>Bigger = every biome is reachable sooner but each patch is smaller.
     * For an overworld cohort of N=280 the measured distances to collect all
     * biomes are 236.8 km (1), 93.4 km (2), 46.3 km (4, default), 21.8 km (8)
     * and 10.5 km (16); the real N depends on the installed biome mods and is
     * logged at runtime.</p>
     */
    public static final int DEFAULT_CELLS_PER_REGION = 4;

    /** Spec §1.2 {@code underground_max_y} default -40: {@code y <= -40} uses the underground cohort. */
    public static final int DEFAULT_UNDERGROUND_MAX_Y = -40;

    /** Spec §2.1 {@code REGION_BLOCKS = REGION_CHUNKS * 16}; the block size of one chunk. */
    public static final int BLOCKS_PER_CHUNK = 16;

    /** Spec §2.1 {@code SHIFT_STRIDE} default = {@code CELLS_PER_REGION}. */
    public static final int SHIFT_STRIDE_PER_CELL = 1;

    /** Sentinel target of a namespace override: "do not take part in dispatch at all". */
    public static final String EXCLUDE = "exclude";

    /**
     * Spec §1.1 step 0 defaults: literal biome ids that must never enter a cohort.
     *
     * <p>{@code terrablender:deferred_placeholder} is an internal placeholder, and
     * {@code minecraft:the_void} only exists for the superflat void preset — neither
     * belongs in a normal world's dispatch.</p>
     */
    private static final Set<String> DEFAULT_EXCLUDED_BIOMES = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of("terrablender:deferred_placeholder", "minecraft:the_void")));

    /** Spec §1.1 step 0 defaults: whole namespaces that must never enter a cohort. */
    private static final Set<String> DEFAULT_EXCLUDED_NAMESPACES = Collections.emptySet();

    /** Explicit additions to the underground biome tags. */
    public static final Set<String> UNDERGROUND_BIOMES = Set.of();

    /** Spec §1.1 step 1 defaults: namespace → dimension (or {@link #EXCLUDE}). */
    private static final Map<String, String> DEFAULT_NAMESPACE_OVERRIDES = defaultNamespaceOverrides();

    private static final Set<String> excludedBiomes = new LinkedHashSet<>(DEFAULT_EXCLUDED_BIOMES);
    private static final Set<String> excludedNamespaces = new LinkedHashSet<>(DEFAULT_EXCLUDED_NAMESPACES);
    private static final Set<String> undergroundBiomes = new LinkedHashSet<>(UNDERGROUND_BIOMES);
    private static final Map<String, String> namespaceOverrides = new LinkedHashMap<>(DEFAULT_NAMESPACE_OVERRIDES);

    private GtSatConfig() {
    }

    /** Spec §1.1 step 1 table, verbatim: aether is a mod with its own dimension, the other three are known. */
    private static Map<String, String> defaultNamespaceOverrides() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("aether", EXCLUDE);
        map.put("betterend", "end");
        map.put("betternether", "nether");
        map.put("incendium", "nether");
        return Collections.unmodifiableMap(map);
    }

    // ------------------------------------------------------------------ 配置 API

    /** Adds or replaces one namespace override; {@code target} is a dimension name or {@link #EXCLUDE}. */
    public static synchronized void setNamespaceOverride(String namespace, String target) {
        namespaceOverrides.put(namespace, target);
    }

    /** Removes one namespace override, falling back to the tag/name rules for that namespace. */
    public static synchronized void clearNamespaceOverride(String namespace) {
        namespaceOverrides.remove(namespace);
    }

    /** Adds one literal biome id (for example {@code terrablender:deferred_placeholder}) to the exclusion list. */
    public static synchronized void excludeBiome(String biomeId) {
        excludedBiomes.add(biomeId);
    }

    /** Excludes a whole namespace from dispatch. */
    public static synchronized void excludeNamespace(String namespace) {
        excludedNamespaces.add(namespace);
    }

    /** Adds one literal biome id to the underground whitelist (spec §1.2 v1.3). */
    public static synchronized void addUndergroundBiome(String biomeId) {
        undergroundBiomes.add(biomeId);
    }

    /** Removes one literal biome id from the underground whitelist. */
    public static synchronized void removeUndergroundBiome(String biomeId) {
        undergroundBiomes.remove(biomeId);
    }

    /** Restores the spec §1.1 defaults. */
    public static synchronized void resetToDefaults() {
        excludedBiomes.clear();
        excludedBiomes.addAll(DEFAULT_EXCLUDED_BIOMES);
        excludedNamespaces.clear();
        excludedNamespaces.addAll(DEFAULT_EXCLUDED_NAMESPACES);
        undergroundBiomes.clear();
        undergroundBiomes.addAll(UNDERGROUND_BIOMES);
        namespaceOverrides.clear();
        namespaceOverrides.putAll(DEFAULT_NAMESPACE_OVERRIDES);
    }

    public static synchronized Map<String, String> namespaceOverrides() {
        return new LinkedHashMap<>(namespaceOverrides);
    }

    public static synchronized Set<String> excludedBiomes() {
        return new LinkedHashSet<>(excludedBiomes);
    }

    public static synchronized Set<String> excludedNamespaces() {
        return new LinkedHashSet<>(excludedNamespaces);
    }

    public static synchronized Set<String> undergroundBiomes() {
        return new LinkedHashSet<>(undergroundBiomes);
    }
}
