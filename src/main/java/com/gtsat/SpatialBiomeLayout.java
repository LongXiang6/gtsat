package com.gtsat;

import com.mojang.serialization.JsonOps;
import com.grandterrain.worldgen.density.EngineTerrainCore;
import com.grandterrain.worldgen.cave.CavityLayout;
import com.grandterrain.worldgen.cave.UndergroundCavities;
import com.grandterrain.worldgen.hydro.SurfaceHabitat;
import com.grandterrain.worldgen.terrain.SpecialTerrainHabitat;
import com.grandterrain.worldgen.shape.HeightMap;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

/** The holder list still declares every biome to Lithostitched and feature sorting. */
public final class SpatialBiomeLayout extends Climate.ParameterList<Holder<Biome>> {
    private static final TagKey<Biome> COMMON_OCEAN = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("c", "is_ocean"));
    private static final TagKey<Biome> COMMON_DEEP_OCEAN = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("c", "is_deep_ocean"));
    private static final TagKey<Biome> COMMON_MOUNTAIN = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("c", "is_mountain"));
    private static final TagKey<Biome> COMMON_DRY = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("c", "is_dry"));
    private static final TagKey<Biome> COMMON_WET = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("c", "is_wet"));
    private static final TagKey<Biome> COMMON_SWAMP = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("c", "is_swamp"));
    private static final int END_CENTER_RADIUS = 1024;

    private final List<Holder<Biome>> main;
    private final List<ClimateBiome> ocean;
    private final List<ClimateBiome> deepOcean;
    private final List<ClimateBiome> shore;
    private final List<ClimateBiome> rivers;
    private final List<ClimateBiome> wetland;
    private final List<ClimateBiome> lowlands;
    private final List<ClimateBiome> highlands;
    private final List<ClimateBiome> skylands;
    private final Holder<Biome> volcanicPeaks;
    private final Holder<Biome> volcanicCrater;
    private final List<Holder<Biome>> underground;
    private final int undergroundMaxY;
    private final boolean overworld;
    private final Holder<Biome> centerEnd;
    private final int regionSize;
    private final long salt;
    private final ThreadLocal<ColumnCache> columns = ThreadLocal.withInitial(ColumnCache::new);

    SpatialBiomeLayout(Climate.ParameterList<Holder<Biome>> points, ResourceKey<Level> dimension,
            List<Holder<Biome>> cohort, List<Holder<Biome>> underground, double sizeRatio,
            int undergroundMaxY) {
        super(points.values());
        this.overworld = dimension.equals(Level.OVERWORLD);
        this.underground = underground;
        this.undergroundMaxY = undergroundMaxY;
        this.main = overworld ? filter(cohort, biome -> !isOcean(biome)
                && !isRiver(biome) && !isShore(biome)) : cohort;
        var climates = overworld ? cohort.stream().map(ClimateBiome::from).toList() : List.<ClimateBiome>of();
        this.skylands = climates.stream().filter(b -> terralithId(b.biome(), "skylands_")).toList();
        this.volcanicCrater = terralith(cohort, "volcanic_crater");
        this.volcanicPeaks = terralith(cohort, "volcanic_peaks");
        this.ocean = climates.stream().filter(b -> isOcean(b.biome()) && !isDeep(b.biome())).toList();
        this.deepOcean = climates.stream().filter(b -> isDeep(b.biome())).toList();
        this.shore = climates.stream().filter(b -> isShore(b.biome())).toList();
        this.rivers = climates.stream().filter(b -> isRiver(b.biome())).toList();
        this.highlands = climates.stream().filter(b -> !isOcean(b.biome())
                && !isRiver(b.biome()) && !isShore(b.biome()) && !terralithId(b.biome(), "skylands_")
                && b.biome() != volcanicCrater && b.biome() != volcanicPeaks).toList();
        this.lowlands = this.highlands.stream().filter(b -> !b.mountain()).toList();
        this.wetland = this.highlands.stream().filter(b -> b.biome().is(COMMON_SWAMP)
                || b.biome().is(Biomes.SWAMP) || b.biome().is(Biomes.MANGROVE_SWAMP)).toList();
        this.centerEnd = dimension.equals(Level.END)
                ? cohort.stream().filter(biome -> biome.is(Biomes.THE_END)).findFirst().orElse(null)
                : null;
        this.regionSize = (int) Math.round(Math.clamp(512.0D * sizeRatio, 64.0D, 4096.0D));
        this.salt = switch (dimension.location().getPath()) {
            case "the_nether" -> 0xA6B8FDF0A2D398B9L;
            case "the_end" -> 0x2C1B3C6D1A3A545DL;
            default -> 0x5D97348DDE0F621BL;
        };
    }

    static boolean isOcean(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(COMMON_OCEAN) || isDeep(biome)
                || path(biome).contains("ocean");
    }

    private static boolean isDeep(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_DEEP_OCEAN) || biome.is(COMMON_DEEP_OCEAN)
                || path(biome).startsWith("deep_") && path(biome).endsWith("_ocean")
                || biome.unwrapKey().orElseThrow().location().toString().equals("regions_unexplored:hyacinth_deeps");
    }

    private static boolean isRiver(Holder<Biome> biome) {
        return !isOcean(biome) && (biome.is(BiomeTags.IS_RIVER)
                || path(biome).equals("river") || path(biome).endsWith("_river"));
    }

    private static boolean isShore(Holder<Biome> biome) {
        return !isOcean(biome) && (biome.is(BiomeTags.IS_BEACH) || path(biome).endsWith("_beach")
                || path(biome).endsWith("_shore"));
    }

    private static boolean isHighland(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_MOUNTAIN) || biome.is(COMMON_MOUNTAIN);
    }

    private static String path(Holder<Biome> biome) {
        return biome.unwrapKey().orElseThrow().location().getPath();
    }

    private static boolean terralithId(Holder<Biome> biome, String pathPrefix) {
        var key = biome.unwrapKey().orElseThrow().location();
        return key.getNamespace().equals("terralith") && key.getPath().startsWith(pathPrefix);
    }

    private static Holder<Biome> terralith(List<Holder<Biome>> cohort, String path) {
        return cohort.stream().filter(biome -> terralithId(biome, path)).findFirst().orElse(null);
    }

    private static List<Holder<Biome>> filter(List<Holder<Biome>> pool, Predicate<Holder<Biome>> predicate) {
        return pool.stream().filter(predicate).toList();
    }

    public boolean overworld() {
        return overworld;
    }

    public Holder<Biome> selectSurface(int quartX, int quartY, int quartZ) {
        int y = QuartPos.toBlock(quartY) + 2;
        Column column = column(quartX, quartZ);
        var habitat = column.habitat();
        if (column.sky() != null && SpecialTerrainHabitat.inSkyIslandBiomeBand(y)) return column.sky();
        var cave = column.cave();
        if (cave != null && y >= cave.span().bottom() && y <= cave.span().top()) return cave.biome();
        if (column.water() != null && (habitat.type() == SurfaceHabitat.Type.SHORE
                || y <= habitat.waterTop() + 8)) {
            return column.water();
        }
        if (column.volcanic() != null && y >= habitat.groundY() - 16) return column.volcanic();
        return column.land();
    }

    private Column column(int quartX, int quartZ) {
        var cache = columns.get();
        var map = EngineTerrainCore.map();
        if (cache.map != map) {
            java.util.Arrays.fill(cache.byPosition, null);
            cache.map = map;
        }
        int index = (quartX & 15) | ((quartZ & 15) << 4);
        var existing = cache.byPosition[index];
        if (existing != null && existing.quartX() == quartX && existing.quartZ() == quartZ) return existing;
        int x = QuartPos.toBlock(quartX) + 2;
        int z = QuartPos.toBlock(quartZ) + 2;
        int seed = (int) EngineTerrainCore.currentSeed();
        long regionSeed = EngineTerrainCore.currentSeed() ^ salt;
        var habitat = SurfaceHabitat.sample(x, z);
        long region = nearestRegion(x, z, regionSeed, regionSize);
        long broad = nearestRegion(x, z, regionSeed ^ 0x058733C5A754AA19L, Math.max(4096, regionSize * 12));
        if ((broad & 3L) == 0L) region = broad;
        int sea = com.grandterrain.config.GrandTerrainConfig.world().seaLevel();
        boolean land = habitat.type() == SurfaceHabitat.Type.LAND || habitat.type() == SurfaceHabitat.Type.SHORE;
        var climate = BiomeClimate.sample(seed, x, z, habitat.ocean() ? sea : habitat.groundY(), sea, land);
        double altitude = Math.clamp((habitat.groundY() - 180) / 330.0, 0.0, 1.0);
        var landPool = habitat.groundY() >= 220 || lowlands.isEmpty() ? highlands : lowlands;
        var surface = choose(landPool, climate, region, 0.74, altitude, null);
        long patch = land ? localPatch(x, z, regionSeed) : 0L;
        if (patch != 0L && landPool.size() > 1) {
            surface = choose(landPool, climate, patch, 0.74, altitude, surface);
        }
        Holder<Biome> water = switch (habitat.type()) {
            case LAND -> null;
            case SHORE -> choose(shore, climate, region, 0.32, 0.0, null);
            case RIVER -> choose(rivers, climate, region, 0.32, 0.0, null);
            case LAKE -> choose(wetland, climate, region, 0.32, 0.0, null);
            case OCEAN, DEEP_OCEAN -> {
                var pool = habitat.type() == SurfaceHabitat.Type.DEEP_OCEAN ? deepOcean : ocean;
                if (pool.isEmpty()) pool = habitat.type() == SurfaceHabitat.Type.DEEP_OCEAN ? ocean : deepOcean;
                yield choose(pool, climate, region, 0.0, 0.0, null);
            }
        };
        if (surface == null) surface = water != null ? water : pick(main, region);
        Holder<Biome> sky = null;
        if (!skylands.isEmpty() && SpecialTerrainHabitat.containsSkyIsland(x, z, habitat.groundY())) {
            var skyClimate = BiomeClimate.sample(seed, x, z, 490, sea, true);
            sky = choose(skylands, skyClimate, region, 1.0, 0.0, null);
        }
        Holder<Biome> volcanic = null;
        if (land && (volcanicPeaks != null || volcanicCrater != null)) {
            volcanic = switch (SpecialTerrainHabitat.volcanoZone(x, z, habitat.groundY())) {
                case CALDERA -> volcanicCrater;
                case CONE -> volcanicPeaks;
                default -> null;
            };
        }
        Column result = new Column(quartX, quartZ, habitat, water, surface, cavern(x, z, seed, regionSeed, habitat),
                volcanic, sky);
        cache.byPosition[index] = result;
        return result;
    }

    private Cave cavern(int x, int z, int seed, long regionSeed, SurfaceHabitat habitat) {
        if (underground.isEmpty() || habitat.ocean()) return null;
        int cellX = Math.floorDiv(x, CavityLayout.CELL_SIZE);
        int cellZ = Math.floorDiv(z, CavityLayout.CELL_SIZE);
        var site = CavityLayout.site(seed, cellX, cellZ, 1.0D);
        if (site == null || site.kind() != CavityLayout.Kind.BIOME_CAVERN) return null;
        var biome = pick(underground, mix(regionSeed ^ ((long) cellX << 32) ^ (cellZ & 0xffffffffL)));
        if (biome.is(Biomes.DEEP_DARK)) site = UndergroundCavities.deepDarkSite(site);
        var span = site.span(x, z);
        return span != null && span.top() <= undergroundMaxY
                && span.top() + CavityLayout.COVER <= habitat.groundY() ? new Cave(span, biome) : null;
    }

    private static Holder<Biome> choose(List<ClimateBiome> pool, BiomeClimate climate, long region,
            double humidityWeight, double altitude, Holder<Biome> exclude) {
        ClimateBiome best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        double preference = 0.27 - 0.21 * climate.anomaly();
        for (var candidate : pool) {
            if (candidate.biome() == exclude) continue;
            double temperature = climate.temperature() - candidate.temperature();
            double humidity = climate.humidity() - candidate.humidity();
            double bias = (mix(region ^ candidate.salt()) >>> 40) * 0x1.0p-24;
            double score = 2.65 * temperature * temperature + humidityWeight * humidity * humidity
                    + preference * bias - (candidate.mountain() ? altitude * 0.075 : 0.0);
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best == null ? null : best.biome();
    }

    private static long localPatch(int x, int z, long seed) {
        final int grid = 384;
        int cellX = Math.floorDiv(x, grid);
        int cellZ = Math.floorDiv(z, grid);
        long hash = mix(seed ^ 0x6838710BA6187F1DL ^ ((long) cellX << 32) ^ (cellZ & 0xffffffffL));
        if ((hash & 3) != 0) return 0;
        int centerX = (4 + (int) ((hash >>> 4) & 15)) * 16;
        int centerZ = (4 + (int) ((hash >>> 8) & 15)) * 16 + 8;
        double dx = (Math.floorMod(x, grid) - centerX) / (13.0D + ((hash >>> 12) & 3));
        // Leave room for BiomeManager's quart-coordinate jitter before the chunk boundary.
        double dz = (Math.floorMod(z, grid) - centerZ) / 5.0D;
        return dx * dx + dz * dz < 1.0D ? hash : 0;
    }

    private record Cave(CavityLayout.Span span, Holder<Biome> biome) {}
    private record Column(int quartX, int quartZ, SurfaceHabitat habitat, Holder<Biome> water,
                          Holder<Biome> land, Cave cave, Holder<Biome> volcanic, Holder<Biome> sky) {}

    private record ClimateBiome(Holder<Biome> biome, double temperature, double humidity,
                                boolean mountain, long salt) {
        static ClimateBiome from(Holder<Biome> holder) {
            Biome biome = holder.value();
            var id = holder.unwrapKey().orElseThrow().location();
            double temperature = BiomeClimate.normalizeTemperature(biome.getBaseTemperature());
            if (isOcean(holder)) {
                String name = id.getPath();
                if (name.contains("frozen")) temperature = 0.05;
                else if (name.contains("cold")) temperature = 0.27;
                else if (name.contains("lukewarm")) temperature = 0.63;
                else if (name.contains("warm")) temperature = 0.90;
            }
            double humidity = Biome.NETWORK_CODEC.encodeStart(JsonOps.INSTANCE, biome).result()
                    .filter(json -> json.isJsonObject() && json.getAsJsonObject().has("downfall"))
                    .map(json -> json.getAsJsonObject().get("downfall").getAsDouble())
                    .orElse(biome.hasPrecipitation() ? 0.5 : 0.12);
            if (holder.is(COMMON_DRY)) humidity = Math.min(humidity, 0.3);
            if (holder.is(COMMON_WET)) humidity = Math.max(humidity, 0.68);
            return new ClimateBiome(holder, temperature, Math.clamp(humidity, 0.0, 1.0),
                    isHighland(holder), mix(id.toString().hashCode()));
        }
    }

    private static final class ColumnCache {
        private HeightMap map;
        private final Column[] byPosition = new Column[256];
    }

    private static Holder<Biome> pick(List<Holder<Biome>> pool, long region) {
        return pool.get((int) Long.remainderUnsigned(region, pool.size()));
    }

    public Holder<Biome> select(int quartX, int quartZ) {
        int x = QuartPos.toBlock(quartX);
        int z = QuartPos.toBlock(quartZ);
        if (centerEnd != null && (long) x * x + (long) z * z <= END_CENTER_RADIUS * END_CENTER_RADIUS) {
            return centerEnd;
        }
        long seed = EngineTerrainCore.currentSeed() ^ salt;
        return pick(main, nearestRegion(x, z, seed, regionSize));
    }

    private static long nearestRegion(int x, int z, long seed, int regionSize) {
        int cellX = Math.floorDiv(x, regionSize);
        int cellZ = Math.floorDiv(z, regionSize);
        int localX = Math.floorMod(x, regionSize);
        int localZ = Math.floorMod(z, regionSize);
        double bestDistance = Double.POSITIVE_INFINITY;
        long nearest = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                long cell = mix(seed ^ ((long) (cellX + dx) << 32) ^ ((cellZ + dz) & 0xffffffffL));
                // Keep sites near their cell centers so a 3x3 search always contains the winner.
                double deltaX = (dx + 0.25D + ((cell >>> 48) & 0xff) / 512.0D) * regionSize - localX;
                double deltaZ = (dz + 0.25D + ((cell >>> 56) & 0xff) / 512.0D) * regionSize - localZ;
                double distance = deltaX * deltaX + deltaZ * deltaZ;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearest = cell;
                }
            }
        }
        return mix(nearest ^ 0x9E3779B97F4A7C15L);
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
