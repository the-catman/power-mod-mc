package com.theca.powermod.mixin;

import com.theca.powermod.power.TimeBubble;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
public class FireBlockMixin {
	// Fire inside a time bubble neither spreads nor burns out; it checks again a little later
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void powermod$freezeInTimeBubble(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		if (TimeBubble.isFrozen(level, pos)) {
			level.scheduleTick(pos, (Block) (Object) this, 30 + random.nextInt(10));
			ci.cancel();
		}
	}
}
