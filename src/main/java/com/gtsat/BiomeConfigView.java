package com.gtsat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Soft, reflection-based reader for the unified config contract
 * {@code grandterrain-v2引擎级架构.md} §3, section {@code [biomes]}.
 *
 * <p>gtsat must not touch the {@code grandterrain} project's source (a teammate
 * owns it) and only reads its public API.  The provider method
 * {@code com.grandterrain.config.GrandTerrainConfig.get().biomes()} does not
 * exist in the grandterrain 0.2.0 jar that ships today, so we resolve it by
 * reflection: if the class / method is present we read the live values; if it
 * is absent we log one line and fall back to the exact defaults from §3.  That
 * keeps this module compiling against the current snapshot and automatically
 * picking up the real values once grandterrain &gt;= 0.3.0 lands.</p>
 *
 * <p>TODO(terrain-engine): once {@code GrandTerrainConfig.get().biomes()} is on
 * the grandterrain classpath, the fields below are read from it directly; the
 * expected accessor names are documented per getter.</p>
 */
public final class BiomeConfigView {
    private static final Logger LOGGER = LoggerFactory.getLogger("gtsat");

    /** §3 defaults — "什么都不改也和现在一样". */
    public static final String DEFAULT_MODE = "inject";
    public static final double DEFAULT_BIOME_SIZE_RATIO = 1.0;
    public static final int DEFAULT_REGION_CHUNKS = 8;
    public static final int DEFAULT_CELLS_PER_REGION = 4;
    public static final int DEFAULT_UNDERGROUND_MAX_Y = -40;
    public static final String DEFAULT_INJECT_PRIORITY = "replace_vanilla_params";
    public static final List<String> DEFAULT_EXCLUDE_BIOMES =
            List.of("terrablender:deferred_placeholder", "minecraft:the_void");
    public static final List<String> DEFAULT_EXCLUDE_NAMESPACES = List.of("aether");
    public static final Map<String, String> DEFAULT_NAMESPACE_OVERRIDES = defaultOverrides();

    private static Map<String, String> defaultOverrides() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("betterend", "end");
        m.put("betternether", "nether");
        m.put("incendium", "nether");
        return Collections.unmodifiableMap(m);
    }

    public final String mode;
    public final double biomeSizeRatio;
    public final int regionChunks;
    public final int cellsPerRegion;
    public final int undergroundMaxY;
    public final String injectPriority;
    public final List<String> excludeBiomes;
    public final List<String> excludeNamespaces;
    public final Map<String, String> namespaceOverrides;
    /** true if the live grandterrain API was actually reachable. */
    public final boolean liveApi;

    private BiomeConfigView(String mode, double ratio, int regionChunks, int cellsPerRegion,
                            int undergroundMaxY, String priority, List<String> exBiomes,
                            List<String> exNs, Map<String, String> overrides, boolean liveApi) {
        this.mode = mode;
        this.biomeSizeRatio = ratio;
        this.regionChunks = regionChunks;
        this.cellsPerRegion = cellsPerRegion;
        this.undergroundMaxY = undergroundMaxY;
        this.injectPriority = priority;
        this.excludeBiomes = exBiomes;
        this.excludeNamespaces = exNs;
        this.namespaceOverrides = overrides;
        this.liveApi = liveApi;
    }

    /** Reads the live contract via reflection; never throws — falls back to §3 defaults. */
    public static BiomeConfigView read() {
        try {
            Class<?> cfgCls = Class.forName("com.grandterrain.config.GrandTerrainConfig");
            Method get = cfgCls.getMethod("get");
            Object cfg = get.invoke(null);
            Method biomes = cfg.getClass().getMethod("biomes");
            Object b = biomes.invoke(cfg);

            String mode = (String) call(b, "mode", DEFAULT_MODE);
            double ratio = ((Number) call(b, "biomeSizeRatio", DEFAULT_BIOME_SIZE_RATIO)).doubleValue();
            int regionChunks = ((Number) call(b, "regionChunks", DEFAULT_REGION_CHUNKS)).intValue();
            int cells = ((Number) call(b, "cellsPerRegion", DEFAULT_CELLS_PER_REGION)).intValue();
            int ugMaxY = ((Number) call(b, "undergroundMaxY", DEFAULT_UNDERGROUND_MAX_Y)).intValue();
            String priority = normaliseInjectPriority(
                    (String) call(b, "injectPriority", DEFAULT_INJECT_PRIORITY));
            @SuppressWarnings("unchecked")
            List<String> exBiomes = (List<String>) call(b, "excludeBiomes", DEFAULT_EXCLUDE_BIOMES);
            @SuppressWarnings("unchecked")
            List<String> exNs = (List<String>) call(b, "excludeNamespaces", DEFAULT_EXCLUDE_NAMESPACES);
            @SuppressWarnings("unchecked")
            Map<String, String> overrides = (Map<String, String>) call(
                    b, "namespaceOverrides", DEFAULT_NAMESPACE_OVERRIDES);

            LOGGER.info("gtsat: read live grandterrain [biomes] config: mode={} ratio={} priority={}",
                    mode, ratio, priority);
            return new BiomeConfigView(mode, ratio, regionChunks, cells, ugMaxY, priority,
                    List.copyOf(exBiomes), List.copyOf(exNs),
                    Collections.unmodifiableMap(new LinkedHashMap<>(overrides)), true);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            LOGGER.warn("gtsat: grandterrain GrandTerrainConfig.get().biomes() not present yet "
                    + "(grandterrain < 0.3.0?); using §3 defaults. (" + e + ")");
        } catch (ReflectiveOperationException | ClassCastException e) {
            LOGGER.error("gtsat: reading grandterrain [biomes] config failed, using §3 defaults", e);
        }
        return defaults();
    }

    public static BiomeConfigView defaults() {
        return new BiomeConfigView(DEFAULT_MODE, DEFAULT_BIOME_SIZE_RATIO, DEFAULT_REGION_CHUNKS,
                DEFAULT_CELLS_PER_REGION, DEFAULT_UNDERGROUND_MAX_Y, DEFAULT_INJECT_PRIORITY,
                DEFAULT_EXCLUDE_BIOMES, DEFAULT_EXCLUDE_NAMESPACES,
                DEFAULT_NAMESPACE_OVERRIDES, false);
    }

    private static Object call(Object target, String method, Object dflt) throws ReflectiveOperationException {
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (NoSuchMethodException e) {
            return dflt;
        }
    }

    /**
     * {@code inject_priority} is kept as a config field for contract stability,
     * but only {@code replace_vanilla_params} is offered.
     *
     * <p>Measured against the <b>real</b> vanilla parameter list (7,593 points,
     * 5 seeds × 100k samples, {@code selftest/AppendCostAnalysis}):
     * {@code addPoints} (append) lets gtsat win <b>83.13%</b> of samples while
     * {@code dispatchAlternateLayout} (replace) wins <b>100%</b>. So append is
     * <i>not</i> a no-op — it is simply strictly worse, and it additionally
     * leaves ~17% of gtsat's own climate band to be taken by vanilla biomes.</p>
     *
     * <p>Any other value is normalised here, once, with an ERROR explaining the
     * measurement rather than pretending the mode is broken.</p>
     */
    static String normaliseInjectPriority(String raw) {
        if (DEFAULT_INJECT_PRIORITY.equals(raw)) {
            return raw;
        }
        LOGGER.error("gtsat: inject_priority={} is not offered. 'append' (addPoints) does work, "
                + "but measured against the real vanilla parameter list it lets gtsat win only "
                + "83.13% of samples (5 seeds x 100k) versus 100% for 'replace_vanilla_params' — "
                + "vanilla keeps ~17% of gtsat's own climate band. Using '{}' instead.",
                raw, DEFAULT_INJECT_PRIORITY);
        return DEFAULT_INJECT_PRIORITY;
    }

    /** Applies this view to the static {@link GtSatConfig} tables so the scheduler path stays consistent. */
    public void applyToSchedulerTables() {
        GtSatConfig.resetToDefaults();
        for (String id : excludeBiomes) {
            GtSatConfig.excludeBiome(id);
        }
        for (String ns : excludeNamespaces) {
            GtSatConfig.excludeNamespace(ns);
        }
        for (Map.Entry<String, String> e : namespaceOverrides.entrySet()) {
            GtSatConfig.setNamespaceOverride(e.getKey(), e.getValue());
        }
    }

    @Override
    public String toString() {
        return "BiomeConfigView[mode=" + mode + ", ratio=" + biomeSizeRatio + ", priority=" + injectPriority
                + ", liveApi=" + liveApi + "]";
    }
}
