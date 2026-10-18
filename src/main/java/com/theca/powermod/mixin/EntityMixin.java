package com.theca.powermod.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
	// Lightning never hurts or ignites the player who summoned it
	@Inject(method = "thunderHit", at = @At("HEAD"), cancellable = true)
	private void powermod$ignoreOwnLightning(ServerLevel level, LightningBolt lightning, CallbackInfo ci) {
		if (lightning.getCause() == (Object) this) {
			ci.cancel();
		}
	}
}
