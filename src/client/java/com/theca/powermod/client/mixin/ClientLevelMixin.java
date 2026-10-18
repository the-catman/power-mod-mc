package com.theca.powermod.client.mixin;

import com.theca.powermod.power.TimeBubble;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class ClientLevelMixin {
	// Frozen entities stop animating, and projectiles stop being predicted forward
	@Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
	private void powermod$freezeInTimeBubble(Entity entity, CallbackInfo ci) {
		if (TimeBubble.isFrozen(entity)) {
			ci.cancel();
		}
	}
}
