package com.theca.powermod.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.theca.powermod.power.TimeBubble;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
	// A frozen entity's previous-tick position, rotation and animation are no longer updated, so
	// blending towards them between ticks would make it jitter. Draw it exactly as it is instead.
	@ModifyVariable(method = "extractEntity", at = @At("HEAD"), argsOnly = true)
	private float powermod$holdFrozenPose(float partialTick, @Local(argsOnly = true) Entity entity) {
		return TimeBubble.isFrozen(entity) ? 1f : partialTick;
	}
}
