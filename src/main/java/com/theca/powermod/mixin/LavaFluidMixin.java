package com.theca.powermod.mixin;

import com.theca.powermod.power.TimeBubble;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LavaFluid.class)
public class LavaFluidMixin {
	// Lava inside a time bubble doesn't set things alight
	@Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
	private void powermod$freezeInTimeBubble(ServerLevel level, BlockPos pos, FluidState state, RandomSource random, CallbackInfo ci) {
		if (TimeBubble.isFrozen(level, pos)) {
			ci.cancel();
		}
	}
}
