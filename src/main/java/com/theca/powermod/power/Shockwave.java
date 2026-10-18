package com.theca.powermod.power;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A 360° ring that rolls outward along the ground, a little further each tick.
 * It throws mobs up and away, kicks up the ground it passes over, and breaks some
 * of the softer blocks in its path.
 */
public final class Shockwave {
	public static final DustParticleOptions ENERGY = new DustParticleOptions(0xC8ECFF, 1.6f);

	private static final List<Shockwave> ACTIVE = new ArrayList<>();
	private static final float SPEED = 1.2f;
	private static final float START_RADIUS = 1.5f;
	private static final float POINT_SPACING = 0.6f;
	// Big rings get sparser points so the particle count per tick stays reasonable
	private static final int MAX_POINTS = 260;
	private static final float BREAK_CHANCE = 0.12f;
	// Dirt, sand, stone, wood and cobblestone break; ores, deepslate and obsidian don't.
	private static final float MAX_BREAKABLE_HARDNESS = 2.5f;

	private final ServerLevel level;
	private final ServerPlayer owner;
	private final Vec3 center;
	private final float maxRadius;
	private final float damage;
	private final float boltChance;
	private final Set<UUID> alreadyHit = new HashSet<>();
	private float radius = START_RADIUS;

	private Shockwave(ServerLevel level, ServerPlayer owner, Vec3 center, float maxRadius, float damage, float boltChance) {
		this.level = level;
		this.owner = owner;
		this.center = center;
		this.maxRadius = maxRadius;
		this.damage = damage;
		this.boltChance = boltChance;
	}

	public static void register() {
		ServerTickEvents.END_LEVEL_TICK.register(level -> ACTIVE.removeIf(wave -> wave.level == level && wave.tick()));
	}

	public static void start(ServerLevel level, ServerPlayer owner, Vec3 center, float maxRadius, float damage) {
		start(level, owner, center, maxRadius, damage, 0);
	}

	/** @param boltChance chance per tick that a lightning bolt strikes somewhere on the ring */
	public static void start(ServerLevel level, ServerPlayer owner, Vec3 center, float maxRadius, float damage, float boltChance) {
		ACTIVE.add(new Shockwave(level, owner, center, maxRadius, damage, boltChance));
	}

	/** Advances the ring one step. Returns true once it has reached its full size. */
	private boolean tick() {
		float inner = radius;
		radius = Math.min(radius + SPEED, maxRadius);
		affectGround();
		hitEntities(inner);
		strikeRing();
		return radius >= maxRadius;
	}

	private void affectGround() {
		int baseY = (int) Math.floor(center.y);
		int points = Math.min(MAX_POINTS, (int) Math.ceil(2 * Math.PI * radius / POINT_SPACING));
		Set<BlockPos> visited = new HashSet<>();
		for (int i = 0; i < points; i++) {
			double angle = 2 * Math.PI * i / points;
			double x = center.x + Math.cos(angle) * radius;
			double z = center.z + Math.sin(angle) * radius;
			BlockPos ground = findSurface(level, (int) Math.floor(x), (int) Math.floor(z), baseY);
			if (ground == null || !visited.add(ground)) {
				continue;
			}

			BlockState state = level.getBlockState(ground);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), x, ground.getY() + 1.1, z, 4, 0.2, 0.2, 0.2, 0.15);
			level.sendParticles(ENERGY, x, ground.getY() + 1.2, z, 1, 0.1, 0.1, 0.1, 0);
			if (level.getRandom().nextFloat() < 0.15f) {
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, ground.getY() + 1.3, z, 2, 0.2, 0.3, 0.2, 0.1);
			}

			// Grass, flowers etc. on top get flattened most of the time
			BlockPos above = ground.above();
			BlockState aboveState = level.getBlockState(above);
			if (!aboveState.isAir() && aboveState.canBeReplaced()) {
				tryBreak(level, owner, above, 0.6f);
			}
			tryBreak(level, owner, ground, BREAK_CHANCE);
		}
	}

	private void strikeRing() {
		if (level.getRandom().nextFloat() >= boltChance) {
			return;
		}
		double angle = level.getRandom().nextDouble() * Math.PI * 2;
		double x = center.x + Math.cos(angle) * radius;
		double z = center.z + Math.sin(angle) * radius;
		BlockPos ground = findSurface(level, (int) Math.floor(x), (int) Math.floor(z), (int) Math.floor(center.y));
		if (ground != null) {
			Storm.strike(level, new Vec3(x, ground.getY() + 1, z), owner);
		}
	}

	private void hitEntities(float inner) {
		AABB area = new AABB(center, center).inflate(radius + 1, 4, radius + 1);
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, area)) {
			if (target == owner || alreadyHit.contains(target.getUUID())) {
				continue;
			}
			if (target instanceof OwnableEntity pet && pet.getOwner() == owner) {
				continue;
			}
			double dx = target.getX() - center.x;
			double dz = target.getZ() - center.z;
			double distance = Math.sqrt(dx * dx + dz * dz);
			if (distance < inner - 0.5 || distance > radius + 0.5) {
				continue;
			}

			alreadyHit.add(target.getUUID());
			float falloff = 1f - 0.5f * (float) (distance / maxRadius);
			target.hurtServer(level, level.damageSources().playerAttack(owner), damage * falloff);
			Vec3 outward = distance > 0.01 ? new Vec3(dx / distance, 0, dz / distance) : Vec3.ZERO;
			target.addDeltaMovement(outward.scale(1.4 * falloff).add(0, 0.5 + 0.4 * falloff, 0));
			target.needsSync = true;
		}
	}

	/** Breaks the block with the given chance, if it is soft enough and the owner may build there. */
	public static void tryBreak(ServerLevel level, ServerPlayer owner, BlockPos pos, float chance) {
		if (level.getRandom().nextFloat() >= chance) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		float hardness = state.getDestroySpeed(level, pos);
		if (state.isAir() || state.liquid() || state.hasBlockEntity() || hardness < 0 || hardness > MAX_BREAKABLE_HARDNESS) {
			return;
		}
		if (!level.mayInteract(owner, pos)) {
			return;
		}
		level.destroyBlock(pos, true, owner, 512);
	}

	/** Finds the top solid block of a column within a few blocks of {@code baseY}, or null. */
	public static BlockPos findSurface(ServerLevel level, int x, int z, int baseY) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, baseY, z);
		// Never load chunks just to draw a ring that ran past the loaded area
		if (!level.isLoaded(pos)) {
			return null;
		}
		pos.move(0, 3, 0);
		for (int i = 0; i < 8; i++) {
			if (level.getBlockState(pos).isSolid() && !level.getBlockState(pos.above()).isSolid()) {
				return pos.immutable();
			}
			pos.move(Direction.DOWN);
		}
		return null;
	}
}
