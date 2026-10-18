package com.theca.powermod.power;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Summons real lightning on behalf of a player.
 *
 * The summoner can't be hurt by their own storm: bolts they caused skip them entirely
 * (see EntityMixin), and for a few seconds after summoning they ignore fire damage,
 * so standing in the fire their own bolts started won't hurt them either.
 */
public final class Storm {
	private static final int FIRE_GRACE_TICKS = 100;
	private static final Map<UUID, Long> FIRE_GRACE_UNTIL = new HashMap<>();

	private Storm() {
	}

	public static void register() {
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (source.is(DamageTypeTags.IS_FIRE) && hasFireGrace(entity)) {
				entity.clearFire();
				return false;
			}
			return true;
		});
	}

	public static void strike(ServerLevel level, Vec3 pos, ServerPlayer summoner) {
		LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
		if (bolt == null) {
			return;
		}
		bolt.snapTo(pos);
		bolt.setCause(summoner);
		level.addFreshEntity(bolt);
		FIRE_GRACE_UNTIL.put(summoner.getUUID(), level.getGameTime() + FIRE_GRACE_TICKS);
	}

	private static boolean hasFireGrace(LivingEntity entity) {
		Long until = FIRE_GRACE_UNTIL.get(entity.getUUID());
		if (until == null) {
			return false;
		}
		if (entity.level().getGameTime() > until) {
			FIRE_GRACE_UNTIL.remove(entity.getUUID());
			return false;
		}
		return true;
	}
}
