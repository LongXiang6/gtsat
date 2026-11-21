package com.gtsat;

import com.grandterrain.worldgen.shape.ValueNoise;

public record BiomeClimate(double temperature, double humidity, double anomaly) {
    private static final int ANOMALY_GRID = 2048;

    public static BiomeClimate sample(int seed, int x, int z, int elevation, int seaLevel, boolean land) {
        double continental = ValueNoise.value(seed, x / 6500.0, z / 6500.0, 0x50C1);
        double regional = ValueNoise.value(seed, x / 2400.0, z / 2400.0, 0x50C4);
        double temperature = Math.clamp((continental * 0.79 + regional * 0.21 - 0.5) * 1.64 + 0.5, 0.0, 1.0);
        double moisture = ValueNoise.value(seed, x / 5300.0, z / 5300.0, 0x50C2);
        double local = ValueNoise.value(seed, x / 1900.0, z / 1900.0, 0x50C3);
        double humidity = Math.clamp((moisture * 0.82 + local * 0.18 - 0.5) * 1.57 + 0.5, 0.0, 1.0);
        temperature = Math.clamp(temperature - Math.max(0, elevation - seaLevel) * 0.00022, 0.0, 1.0);
        if (!land) return new BiomeClimate(temperature, humidity, 0.0);

        int cellX = Math.floorDiv(x, ANOMALY_GRID);
        int cellZ = Math.floorDiv(z, ANOMALY_GRID);
        if (ValueNoise.hash01(seed, cellX, cellZ, 0x50C5) >= 1.0 / 16.0) {
            return new BiomeClimate(temperature, humidity, 0.0);
        }
        double dx = Math.floorMod(x, ANOMALY_GRID) - (768 + 512 * ValueNoise.hash01(seed, cellX, cellZ, 0x50C6));
        double dz = Math.floorMod(z, ANOMALY_GRID) - (768 + 512 * ValueNoise.hash01(seed, cellX, cellZ, 0x50C7));
        double radiusSquared = dx * dx + dz * dz;
        if (radiusSquared >= 280 * 280) return new BiomeClimate(temperature, humidity, 0.0);
        double strength = 1.0 - smooth((Math.sqrt(radiusSquared) - 135.0) / 145.0);
        double axis = 2.0 * Math.PI * ValueNoise.hash01(seed, cellX, cellZ, 0x50C8);
        double distance = dx * Math.cos(axis) + dz * Math.sin(axis);
        double extreme = smooth((distance + 12.0) / 24.0);
        return new BiomeClimate(temperature + (extreme - temperature) * strength, humidity, strength);
    }

    static double normalizeTemperature(double value) {
        return Math.clamp((value + 0.65) / 2.65, 0.0, 1.0);
    }

    private static double smooth(double value) {
        double t = Math.clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }
}
