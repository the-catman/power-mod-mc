package com.theca.powermod.power;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Storm Hammer's Ultracharge, on top of a full Skyfall: one enormous blast at the impact,
 * a ring of secondary explosions tearing outwards, a three-second lightning storm over the
 * whole area, everything nearby hurled into the air, and a second, far wider shockwave.
 * The wielder is never hurt by any of it.
 */
public final class Cataclysm {
	private static final List<Cataclysm> ACTIVE = new ArrayList<>();
	private static final int DURATION = 60;
	private static final float CORE_POWER = 12;
	private static final float RING_POWER = 6;
	private static final int RING_BLASTS = 10;
	private static final double STORM_RADIUS = 30;
	private static final double HURL_RADIUS = 24;

	private final ServerLevel level;
	private final ServerPlayer owner;
	private final Vec3 centre;
	private int age;

	private Cataclysm(ServerLevel level, ServerPlayer owner, Vec3 centre) {
		this.level = level;
		this.owner = owner;
		this.centre = centre;
	}

	public static void register() {
		ServerTickEvents.END_LEVEL_TICK.register(level -> ACTIVE.removeIf(cataclysm -> cataclysm.level == level && cataclysm.tick()));
	}

	public static void start(ServerLevel level, ServerPlayer owner, Vec3 centre) {
		ACTIVE.add(new Cataclysm(level, owner, centre));
	}

	/** Returns true once it's over. */
	private boolean tick() {
		RandomSource random = level.getRandom();
		if (age == 0) {
			coreBlast();
		}
		// Secondary blasts ripple outwards over the first second or so
		if (age > 0 && age % 2 == 0 && age / 2 <= RING_BLASTS) {
			double angle = random.nextDouble() * Math.PI * 2;
			double distance = 10 + random.nextDouble() * 10;
			Vec3 spot = surface(centre.x + Math.cos(angle) * distance, centre.z + Math.sin(angle) * distance);
			if (spot != null) {
				level.explode(owner, null, new SparingExplosion(owner), spot.x, spot.y, spot.z, RING_POWER, false, Level.ExplosionInteraction.TNT);
			}
		}
		if (age == 10) {
			Shockwave.start(level, owner, centre, 96, 24, 0.8f);
		}
		// Lightning lashes the whole area, two bolts a tick
		for (int i = 0; i < 2; i++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double distance = random.nextDouble() * STORM_RADIUS;
			Vec3 spot = surface(centre.x + Math.cos(angle) * distance, centre.z + Math.sin(angle) * distance);
			if (spot != null) {
				Storm.strike(level, spot, owner);
			}
		}
		return ++age >= DURATION;
	}

	private void coreBlast() {
		level.explode(owner, null, new SparingExplosion(owner), centre.x, centre.y, centre.z, CORE_POWER, true, Level.ExplosionInteraction.TNT);

		// Blinding flash and a column of light far into the sky
		level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF), true, false, centre.x, centre.y + 2, centre.z, 20, 6, 3, 6, 0);
		for (double dy = 0; dy < 120; dy += 1) {
			level.sendParticles(ParticleTypes.END_ROD, true, false, centre.x, centre.y + dy, centre.z, 6, 3, 0.5, 3, 0.05);
			level.sendParticles(Shockwave.ENERGY, true, false, centre.x, centre.y + dy, centre.z, 3, 4, 0.5, 4, 0);
		}
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, true, false, centre.x, centre.y + 1, centre.z, 12, 8, 2, 8, 0);

		// Heard from far away
		for (Vec3 at : new Vec3[] {centre, owner.position()}) {
			level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 10f, 0.4f);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.TRIDENT_THUNDER, SoundSource.PLAYERS, 10f, 0.5f);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 6f, 0.5f);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 10f, 0.6f);
		}

		// Everything nearby is hurled up and away
		for (Entity entity : level.getEntities(owner, new AABB(centre, centre).inflate(HURL_RADIUS))) {
			if (entity.isSpectator() || entity instanceof OwnableEntity pet && pet.getOwner() == owner) {
				continue;
			}
			Vec3 away = entity.position().subtract(centre);
			double distance = away.length();
			if (distance > HURL_RADIUS) {
				continue;
			}
			double force = 1 - distance / HURL_RADIUS;
			Vec3 outward = distance > 0.01 ? new Vec3(away.x, 0, away.z).normalize() : Vec3.ZERO;
			entity.addDeltaMovement(outward.scale(2.5 * force).add(0, 0.6 + 1.6 * force, 0));
			entity.needsSync = true;
		}
	}

	private Vec3 surface(double x, double z) {
		BlockPos ground = Shockwave.findSurface(level, Mth.floor(x), Mth.floor(z), Mth.floor(centre.y), 12, 24);
		return ground == null ? null : new Vec3(x, ground.getY() + 1, z);
	}
}
