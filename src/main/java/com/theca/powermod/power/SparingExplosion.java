package com.theca.powermod.power;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;

/** Explosion rules that never hurt or knock back the one who set it off. */
public final class SparingExplosion extends ExplosionDamageCalculator {
	private final Entity spared;

	public SparingExplosion(Entity spared) {
		this.spared = spared;
	}

	@Override
	public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
		return entity != spared && super.shouldDamageEntity(explosion, entity);
	}

	@Override
	public float getKnockbackMultiplier(Entity entity) {
		return entity == spared ? 0 : super.getKnockbackMultiplier(entity);
	}
}
