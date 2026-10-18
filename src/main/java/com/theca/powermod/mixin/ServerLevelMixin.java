package com.theca.powermod.mixin;

import com.theca.powermod.power.TimeBubble;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {
	// Entities inside a time bubble skip their tick entirely (their passengers along with them)
	@Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
	private void powermod$freezeInTimeBubble(Entity entity, CallbackInfo ci) {
		if (TimeBubble.isFrozen(entity)) {
			// Still count down the post-hit immunity, or a frozen mob could only be hit once
			EntityAccessor accessor = (EntityAccessor) entity;
			if (accessor.powermod$getInvulnerableTime() > 0) {
				accessor.powermod$setInvulnerableTime(accessor.powermod$getInvulnerableTime() - 1);
			}
			ci.cancel();
		}
	}
}
