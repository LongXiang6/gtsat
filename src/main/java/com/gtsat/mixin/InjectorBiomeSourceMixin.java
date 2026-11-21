package com.gtsat.mixin;

import com.mojang.serialization.MapCodec;
import com.gtsat.SpatialBiomeLayout;
import dev.worldgen.lithostitched.api.worldgen.biomeinjector.BiomeInjector;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.DispatchAlternateLayout;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource", remap = false)
abstract class InjectorBiomeSourceMixin {
    @Shadow @Final
    private Map<MapCodec<? extends BiomeInjector>, List<BiomeInjector>> injectorsByType;

    @Inject(method = "getNoiseBiome", at = @At("HEAD"), cancellable = true)
    private void gtsat$applyOverworldHabitat(int quartX, int quartY, int quartZ, Climate.Sampler sampler,
            CallbackInfoReturnable<Holder<Biome>> callback) {
        var layouts = injectorsByType.get(DispatchAlternateLayout.CODEC);
        if (layouts == null) return;
        // Forced and replacement layouts can bypass or undo the dispatch search.
        for (var entry : layouts) {
            if (entry instanceof DispatchAlternateLayout alternate
                    && alternate.points() instanceof SpatialBiomeLayout layout && layout.overworld()) {
                callback.setReturnValue(layout.selectSurface(quartX, quartY, quartZ));
                return;
            }
        }
    }

    @Redirect(method = "getNoiseBiome", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/biome/Climate$ParameterList;findValue"
                    + "(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Ljava/lang/Object;"), require = 1)
    private Object gtsat$resolveLayout(Climate.ParameterList<?> layout, Climate.TargetPoint target,
            int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        return layout instanceof SpatialBiomeLayout spatial
                ? spatial.select(quartX, quartZ) : layout.findValue(target);
    }
}
