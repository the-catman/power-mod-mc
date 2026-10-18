package com.theca.powermod.power;

import java.util.ArrayList;
import java.util.Arrays;
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
	// Where the ground breaks, cracks run down this many blocks near the centre, fewer further out
	private static final int MAX_CRACK_DEPTH = 4;
	private static final float CRACK_CHANCE = 0.5f;
	// The ring tracks the ground height separately in each direction, so it can roll down hills,
	// over cliff edges and into valleys instead of only finding ground near where it started
	private static final int HEADINGS = 360;
	private static final int CLIMB = 4;
	private static final int DROP = 12;
	// How high a cliff face the ring can run up in one step
	private static final int WALL = 16;
	// Share of broken ground blocks thrown up as debris instead of shattering, with a cap per
	// step so a big ring doesn't fill the world with flying blocks
	private static final float DEBRIS_SHARE = 0.4f;
	private static final int MAX_DEBRIS_PER_TICK = 4;
	// The jolt also reaches up to cave ceilings and tree canopies this far above the ground
	private static final int REACH_OVERHEAD = 8;
	private static final float OVERHEAD_BREAK_CHANCE = 0.06f;
	// Dirt, sand, stone, wood and cobblestone break; ores, deepslate and obsidian don't.
	private static final float MAX_BREAKABLE_HARDNESS = 2.5f;

	private final ServerLevel level;
	private final ServerPlayer owner;
	private final Vec3 center;
	private final float maxRadius;
	private final float damage;
	private final float boltChance;
	private final Set<UUID> alreadyHit = new HashSet<>();
	// Last ground height found in each direction
	private final int[] groundY = new int[HEADINGS];
	private float radius = START_RADIUS;

	private Shockwave(ServerLevel level, ServerPlayer owner, Vec3 center, float maxRadius, float damage, float boltChance) {
		this.level = level;
		this.owner = owner;
		this.center = center;
		this.maxRadius = maxRadius;
		this.damage = damage;
		this.boltChance = boltChance;
		Arrays.fill(groundY, (int) Math.floor(center.y) - 1);
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

	private static int heading(double angle) {
		return Math.floorMod((int) Math.floor(angle / (2 * Math.PI) * HEADINGS), HEADINGS);
	}

	private void affectGround() {
		// Strongest near the centre, fading towards the edge
		float strength = 1 - radius / maxRadius;
		int crackDepth = Math.round(MAX_CRACK_DEPTH * strength);
		int debris = 0;
		int points = Math.min(MAX_POINTS, (int) Math.ceil(2 * Math.PI * radius / POINT_SPACING));
		Set<BlockPos> visited = new HashSet<>();
		for (int i = 0; i < points; i++) {
			double angle = 2 * Math.PI * i / points;
			double x = center.x + Math.cos(angle) * radius;
			double z = center.z + Math.sin(angle) * radius;
			int heading = heading(angle);
			BlockPos ground = followGround((int) Math.floor(x), (int) Math.floor(z), groundY[heading]);
			if (ground == null || !visited.add(ground)) {
				continue;
			}
			groundY[heading] = ground.getY();

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
			shakeOverhead(ground, x, z);
			if (level.getRandom().nextFloat() < BREAK_CHANCE && canBreak(level, owner, ground)) {
				// Some of the broken ground is hurled up and outwards; the rest shatters
				boolean flung = false;
				if (debris < MAX_DEBRIS_PER_TICK && level.getRandom().nextFloat() < DEBRIS_SHARE) {
					double out = 0.2 + 0.4 * strength + level.getRandom().nextDouble() * 0.2;
					double up = 0.5 + 0.5 * strength + level.getRandom().nextDouble() * 0.3;
					flung = Debris.fling(level, ground, new Vec3(Math.cos(angle) * out, up, Math.sin(angle) * out));
				}
				if (flung) {
					debris++;
				} else {
					level.destroyBlock(ground, true, owner, 512);
				}
				// Cracks split down into the ground below
				for (int depth = 1; depth <= crackDepth; depth++) {
					if (!tryBreak(level, owner, ground.below(depth), CRACK_CHANCE)) {
						break;
					}
				}
			}
		}
	}

	/**
	 * The ring's jolt reaches the first solid block overhead, if any is close: dust shakes off
	 * cave ceilings and canopies, and now and then a block drops loose.
	 */
	private void shakeOverhead(BlockPos ground, double x, double z) {
		BlockPos.MutableBlockPos pos = ground.mutable().move(Direction.UP, 2);
		for (int i = 0; i < REACH_OVERHEAD; i++, pos.move(Direction.UP)) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || state.canBeReplaced()) {
				continue;
			}
			level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, state), x, pos.getY() - 0.1, z, 2, 0.3, 0, 0.3, 0);
			if (level.getRandom().nextFloat() < OVERHEAD_BREAK_CHANCE && canBreak(level, owner, pos)) {
				// Plain blocks drop down as debris; anything else just breaks
				if (!Debris.fling(level, pos.immutable(), new Vec3(0, -0.1, 0))) {
					level.destroyBlock(pos, true, owner, 512);
				}
			}
			return;
		}
	}

	/** The ground in this column near {@code lastY}: up or down a slope, down a drop, or up a cliff face. */
	private BlockPos followGround(int x, int z, int lastY) {
		BlockPos ground = findSurface(level, x, z, lastY, CLIMB, DROP);
		if (ground != null || !level.isLoaded(new BlockPos(x, lastY, z))) {
			return ground;
		}
		// Ran into a cliff: climb it to the top
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, lastY + CLIMB + 1, z);
		for (int i = 0; i < WALL; i++) {
			if (level.getBlockState(pos).isSolid() && !level.getBlockState(pos.above()).isSolid()) {
				return pos.immutable();
			}
			pos.move(Direction.UP);
		}
		return null;
	}

	private void strikeRing() {
		if (level.getRandom().nextFloat() >= boltChance) {
			return;
		}
		double angle = level.getRandom().nextDouble() * Math.PI * 2;
		double x = center.x + Math.cos(angle) * radius;
		double z = center.z + Math.sin(angle) * radius;
		BlockPos ground = followGround((int) Math.floor(x), (int) Math.floor(z), groundY[heading(angle)]);
		if (ground != null) {
			Storm.strike(level, new Vec3(x, ground.getY() + 1, z), owner);
		}
	}

	private void hitEntities(float inner) {
		AABB area = new AABB(center, center).inflate(radius + 1, DROP + 4, radius + 1);
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
			// Only things near the ground where the ring is passing, not on a ledge far above or in a cave below
			int ground = groundY[heading(Math.atan2(dz, dx))];
			if (Math.abs(target.getY() - (ground + 1)) > 4) {
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

	/**
	 * Breaks the block with the given chance, if it is soft enough and the owner may build there.
	 * Returns whether it broke.
	 */
	public static boolean tryBreak(ServerLevel level, ServerPlayer owner, BlockPos pos, float chance) {
		if (level.getRandom().nextFloat() >= chance || !canBreak(level, owner, pos)) {
			return false;
		}
		return level.destroyBlock(pos, true, owner, 512);
	}

	/** Whether a shockwave may break this block: soft enough, not a container, and the owner may build there. */
	public static boolean canBreak(ServerLevel level, ServerPlayer owner, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		float hardness = state.getDestroySpeed(level, pos);
		if (state.isAir() || state.liquid() || state.hasBlockEntity() || hardness < 0 || hardness > MAX_BREAKABLE_HARDNESS) {
			return false;
		}
		return level.mayInteract(owner, pos);
	}

	/** Finds the top solid block of a column within a few blocks of {@code baseY}, or null. */
	public static BlockPos findSurface(ServerLevel level, int x, int z, int baseY) {
		return findSurface(level, x, z, baseY, 3, 4);
	}

	/** Finds the top solid block of a column from {@code up} blocks above {@code baseY} to {@code down} below, or null. */
	public static BlockPos findSurface(ServerLevel level, int x, int z, int baseY, int up, int down) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, baseY, z);
		// Never load chunks just to draw a ring that ran past the loaded area
		if (!level.isLoaded(pos)) {
			return null;
		}
		pos.move(0, up, 0);
		for (int i = 0; i <= up + down; i++) {
			if (level.getBlockState(pos).isSolid() && !level.getBlockState(pos.above()).isSolid()) {
				return pos.immutable();
			}
			pos.move(Direction.DOWN);
		}
		return null;
	}
}
