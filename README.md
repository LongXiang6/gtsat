# GrandTerrain Biome Dispatcher

Places registered biomes by climate, habitat, terrain and elevation.

## Installation

Java 21, Minecraft 1.21.1, and NeoForge. Tested with 21.1.265. Download `gtsat-0.1.2-p3-compat.jar` from Releases. Install GrandTerrain 0.9.15 and Lithostitched 1.8.1 for NeoForge 1.21.1. Use a fresh world to evaluate biome and terrain distribution. The tested configuration uses the default world preset.

依照气候、水体、地貌和海拔，为各维度分配原版与模组群系。

## Build

Use JDK 21 and Python 3.11+. Create a text file with one absolute JAR path per line for the mapped Minecraft 1.21.1 and NeoForge 21.1.265 libraries, plus `grandterrain-0.9.15-caves-snow.jar`, `lithostitched-1.8.1-neoforge-21.1.jar`.

```sh
python build.py --classpath-file classpath.txt --output gtsat-0.1.2-p3-compat.jar
```

## License and feedback

MIT. 

Report issues here with reproduction steps and your Minecraft, NeoForge and mod versions.
