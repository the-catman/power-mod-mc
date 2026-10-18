package com.theca.powermod.power;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * A burning rock that streaks down at an angle and blows a scorched crater where it lands,
 * sometimes leaving a chunk of ore in the middle. The one who called it is never hurt or
 * thrown by its blast.
 */
public final class Meteor {
	private static final List<Meteor> ACTIVE = new ArrayList<>();
	// Blocks per tick
	private static final double SPEED = 3;
	// How far along its path a meteor starts from its landing spot
	private static final double FALL_DISTANCE = 80;
	private static final float ORE_CHANCE = 0.2f;

	private final ServerLevel level;
	private final ServerPlayer owner;
	private final Vec3 velocity;
	private final float power;
	private Vec3 pos;
	private int delay;

	private Meteor(ServerLevel level, ServerPlayer owner, Vec3 pos, Vec3 velocity, float power, int delay) {
		this.level = level;
		this.owner = owner;
		this.pos = pos;
		this.velocity = velocity;
		this.power = power;
		this.delay = delay;
	}

	public static void register() {
		ServerTickEvents.END_LEVEL_TICK.register(level -> ACTIVE.removeIf(meteor -> meteor.level == level && meteor.tick()));
	}

	/**
	 * Calls down {@code count} meteors one after another, landing at random within {@code spread}
	 * blocks of {@code target}. They all come in from the same side of the sky, like a shower.
	 *
	 * @param power explosion strength of each meteor (TNT is 4)
	 */
	public static void barrage(ServerLevel level, ServerPlayer owner, Vec3 target, int count, double spread, float power) {
		RandomSource random = owner.getRandom();
		double heading = random.nextDouble() * Math.PI * 2;
		int delay = 0;
		for (int i = 0; i < count; i++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double distance = Math.sqrt(random.nextDouble()) * spread;
			Vec3 landing = target.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
			// Steep, between 55 and 70 degrees, with a little scatter in direction
			double pitch = Math.toRadians(55 + random.nextDouble() * 15);
			double yaw = heading + (random.nextDouble() - 0.5) * 0.3;
			Vec3 down = new Vec3(Math.cos(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.sin(yaw) * Math.cos(pitch));
			Vec3 start = landing.subtract(down.scale(FALL_DISTANCE));
			ACTIVE.add(new Meteor(level, owner, start, down.scale(SPEED), power, delay));
			delay += 3 + random.nextInt(6);
		}
	}

	/** Moves the meteor along. Returns true once it has landed or left the loaded world. */
	private boolean tick() {
		if (delay > 0) {
			delay--;
			if (delay == 0) {
				announce();
			}
			return false;
		}
		Vec3 next = pos.add(velocity);
		if (!level.hasChunk(SectionPos.blockToSectionCoord(next.x), SectionPos.blockToSectionCoord(next.z)) || next.y < level.getMinY()) {
			return true;
		}
		BlockHitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()));
		Vec3 end = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : next;
		trail(pos, end);
		pos = end;
		if (hit.getType() == HitResult.Type.BLOCK) {
			impact(hit.getBlockPos());
			return true;
		}
		return false;
	}

	// A rising whistle at the landing area as each meteor sets off, so you hear it coming
	private void announce() {
		Vec3 landing = pos.add(velocity.scale(FALL_DISTANCE / SPEED));
		level.playSound(null, landing.x, landing.y, landing.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 6f, 0.5f);
		level.playSound(null, landing.x, landing.y, landing.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 6f, 0.4f);
	}

	private void trail(Vec3 from, Vec3 to) {
		RandomSource random = level.getRandom();
		for (int i = 0; i < 6; i++) {
			Vec3 point = from.lerp(to, i / 6.0);
			// Overriding the limiter keeps meteors visible high in the sky and far away
			level.sendParticles(ParticleTypes.FLAME, true, false, point.x, point.y, point.z, 3, 0.25, 0.25, 0.25, 0.02);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, point.x, point.y, point.z, 1, 0.3, 0.3, 0.3, 0.01);
			if (random.nextInt(3) == 0) {
				level.sendParticles(ParticleTypes.LAVA, true, false, point.x, point.y, point.z, 1, 0.2, 0.2, 0.2, 0);
			}
		}
	}

	private void impact(BlockPos struck) {
		Vec3 at = pos;
		level.explode(owner, null, new SparingExplosion(owner), at.x, at.y, at.z, power, true, Level.ExplosionInteraction.TNT);
		level.sendParticles(ParticleTypes.LAVA, true, false, at.x, at.y + 0.5, at.z, 20, 1, 0.5, 1, 0);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, at.x, at.y + 1, at.z, 30, 1.5, 1, 1.5, 0.05);
		scorch(at, power + 1);
		leaveOre(struck);
	}

	// Blackens the crater's surface and leaves pockets of magma
	private void scorch(Vec3 centre, float radius) {
		RandomSource random = level.getRandom();
		int r = (int) Math.ceil(radius);
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(centre).offset(-r, -r, -r), BlockPos.containing(centre).offset(r, r, r))) {
			if (pos.distToCenterSqr(centre) > radius * radius || !level.getBlockState(pos.above()).isAir()) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || !state.isSolidRender() || state.hasBlockEntity() || state.getDestroySpeed(level, pos) < 0) {
				continue;
			}
			float roll = random.nextFloat();
			if (roll < 0.15f) {
				level.setBlock(pos, Blocks.MAGMA_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
			} else if (roll < 0.5f) {
				level.setBlock(pos, Blocks.BLACKSTONE.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
	}

	// Sometimes the meteor's core survives as a block of ore at the bottom of the crater
	private void leaveOre(BlockPos struck) {
		RandomSource random = level.getRandom();
		if (random.nextFloat() >= ORE_CHANCE) {
			return;
		}
		// Find the crater floor below where it struck
		BlockPos floor = struck;
		for (int i = 0; i < 8 && level.getBlockState(floor).isAir(); i++) {
			floor = floor.below();
		}
		BlockState current = level.getBlockState(floor);
		if (current.isAir() || current.hasBlockEntity() || current.getDestroySpeed(level, floor) < 0) {
			return;
		}
		float roll = random.nextFloat();
		Block ore = roll < 0.5f ? Blocks.IRON_ORE : roll < 0.8f ? Blocks.GOLD_ORE : roll < 0.95f ? Blocks.ANCIENT_DEBRIS : Blocks.DIAMOND_ORE;
		level.setBlock(floor, ore.defaultBlockState(), Block.UPDATE_ALL);
	}
}
