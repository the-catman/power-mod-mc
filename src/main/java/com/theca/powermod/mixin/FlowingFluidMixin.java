package com.theca.powermod.mixin;

import com.theca.powermod.power.TimeBubble;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowingFluid.class)
public class FlowingFluidMixin {
	// Water and lava inside a time bubble don't flow; they try again once the usual delay has passed
	@Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V",
			at = @At("HEAD"), cancellable = true)
	private void powermod$freezeInTimeBubble(ServerLevel level, BlockPos pos, BlockState blockState, FluidState fluidState, CallbackInfo ci) {
		if (TimeBubble.isFrozen(level, pos)) {
			FlowingFluid fluid = (FlowingFluid) (Object) this;
			level.scheduleTick(pos, fluid, fluid.getTickDelay(level));
			ci.cancel();
		}
	}
}
