package com.theca.powermod.power;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * A searing orb fired from the Staff of Drought. It flies straight, stops at the first block,
 * creature, or water or lava surface it meets, and bursts into a sphere of drought that
 * spreads out from the impact over a moment: water and lava vanish, waterlogged blocks dry
 * out, plants wither into dead bushes, leaves crumble away, soil cracks into coarse dirt,
 * ice and snow are gone. Creatures caught in it grow parched, and anything that lives in
 * water suffocates.
 */
public final class DroughtBolt {
	private static final List<DroughtBolt> ACTIVE = new ArrayList<>();
	private static final int MAX_FLIGHT_TICKS = 60;
	// Ticks the drought takes to spread to its full size after the orb bursts
	private static final int SPREAD_TICKS = 12;
	private static final DustParticleOptions EMBER = new DustParticleOptions(0xE0A040, 1.4f);

	private final ServerLevel level;
	private final ServerPlayer owner;
	private final Vec3 velocity;
	private final double radius;
	private final float power;
	private Vec3 pos;
	private int age;
	// Where it burst, once it has; then how far the drought has spread
	private Vec3 impact;
	private double spread;

	private DroughtBolt(ServerLevel level, ServerPlayer owner, Vec3 pos, Vec3 velocity, double radius, float power) {
		this.level = level;
		this.owner = owner;
		this.pos = pos;
		this.velocity = velocity;
		this.radius = radius;
		this.power = power;
	}

	public static void register() {
		ServerTickEvents.END_LEVEL_TICK.register(level -> ACTIVE.removeIf(bolt -> bolt.level == level && bolt.tick()));
	}

	/**
	 * Fires an orb from the player's eyes along their aim.
	 *
	 * @param power  0 to 1, how charged it was
	 * @param radius how far the drought spreads from where it bursts
	 */
	public static void fire(ServerLevel level, ServerPlayer player, float power, double radius) {
		Vec3 velocity = player.getLookAngle().scale(1.5 + 2.5 * power);
		ACTIVE.add(new DroughtBolt(level, player, player.getEyePosition(), velocity, radius, power));
	}

	/** Returns true once it's over. */
	private boolean tick() {
		if (owner.isRemoved() && impact == null) {
			return true;
		}
		return impact == null ? fly() : spreadDrought();
	}

	private boolean fly() {
		Vec3 next = pos.add(velocity);
		if (age++ > MAX_FLIGHT_TICKS || !level.isLoaded(BlockPos.containing(next))) {
			return true;
		}
		// Water and lava are what this is for, so it stops at their surface rather than passing through
		HitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()));
		Vec3 end = hit.getType() == HitResult.Type.MISS ? next : hit.getLocation();
		EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(level, owner, pos, end, new AABB(pos, end).inflate(1),
				entity -> !entity.isSpectator() && entity.isPickable() && !(entity instanceof OwnableEntity pet && pet.getOwner() == owner), 0.3f);
		if (entityHit != null) {
			hit = entityHit;
			end = entityHit.getLocation();
			if (entityHit.getEntity() instanceof LivingEntity victim) {
				victim.hurtServer(level, level.damageSources().dryOut(), 2 + 4 * power);
			}
		}

		// Trail of heat and embers
		for (int i = 1; i <= 4; i++) {
			Vec3 point = pos.lerp(end, i / 4.0);
			level.sendParticles(EMBER, true, false, point.x, point.y, point.z, 1, 0.05, 0.05, 0.05, 0);
			level.sendParticles(i % 2 == 0 ? ParticleTypes.SMALL_FLAME : ParticleTypes.WHITE_ASH, true, false,
					point.x, point.y, point.z, 1, 0.1, 0.1, 0.1, 0.01);
		}
		if (age % 4 == 0) {
			level.playSound(null, end.x, end.y, end.z, SoundEvents.BLAZE_BURN, SoundSource.PLAYERS, 0.6f, 1.4f);
		}

		if (hit.getType() == HitResult.Type.MISS) {
			pos = next;
			return false;
		}
		burst(hit instanceof BlockHitResult blockHit ? end.add(blockHit.getDirection().getStepX() * 0.1, blockHit.getDirection().getStepY() * 0.1, blockHit.getDirection().getStepZ() * 0.1) : end);
		return false;
	}

	private void burst(Vec3 at) {
		impact = at;
		level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFC080), true, false, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, at.x, at.y, at.z, 20, 0.5, 0.5, 0.5, 0.1);
		level.sendParticles(EMBER, true, false, at.x, at.y, at.z, 40, radius / 3, radius / 3, radius / 3, 0);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 2f, 0.5f);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 2f, 0.5f);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.SAND_IDLE, SoundSource.PLAYERS, 3f, 0.6f);
		parchCreatures();
	}

	/** Dries out the next shell of the sphere. Returns true once it has spread all the way. */
	private boolean spreadDrought() {
		double inner = spread;
		spread = Math.min(radius, spread + radius / SPREAD_TICKS);
		int changed = 0;
		int r = Mth.ceil(spread);
		BlockPos centre = BlockPos.containing(impact);
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
			double distanceSqr = pos.distToCenterSqr(impact);
			if (distanceSqr > spread * spread || distanceSqr < inner * inner && inner > 0) {
				continue;
			}
			if (!level.isLoaded(pos) || !level.mayInteract(owner, pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			BlockState dry = dried(state);
			if (dry == null) {
				continue;
			}
			level.setBlock(pos, dry, Block.UPDATE_ALL);
			changed++;
			// Steam off water, smoke off lava, dust off everything else; only some of them, to keep the particle count down
			if (level.getRandom().nextInt(4) != 0) {
				continue;
			}
			double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
			if (state.getFluidState().is(FluidTags.LAVA)) {
				level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 1, 0.3, 0.3, 0.3, 0.02);
			} else if (!state.getFluidState().isEmpty() || state.is(Blocks.ICE) || state.is(Blocks.SNOW_BLOCK)) {
				level.sendParticles(ParticleTypes.CLOUD, x, y, z, 1, 0.3, 0.3, 0.3, 0.03);
			} else {
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), x, y, z, 3, 0.3, 0.3, 0.3, 0.05);
			}
		}
		heatHaze();
		// One sizzle every few ticks stands in for everything drying out, so the sound pool doesn't fill up
		if (changed > 0 && age++ % 3 == 0) {
			level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 1.5f, 0.6f);
		}
		return spread >= radius;
	}

	// Creatures in the area grow hungry and parched; water creatures dry out and suffocate
	private void parchCreatures() {
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(impact, impact).inflate(radius))) {
			if (entity == owner || entity.isSpectator() || entity.distanceToSqr(impact) > radius * radius
					|| entity instanceof OwnableEntity pet && pet.getOwner() == owner) {
				continue;
			}
			entity.addEffect(new MobEffectInstance(MobEffects.HUNGER, 100 + Math.round(200 * power), 2), owner);
			if (entity instanceof WaterAnimal) {
				entity.hurtServer(level, level.damageSources().dryOut(), 4 + 8 * power);
			}
		}
	}

	// Shimmering heat and drifting dust rising off the spreading drought
	private void heatHaze() {
		RandomSource random = level.getRandom();
		int count = 4 + (int) spread * 2;
		for (int i = 0; i < count; i++) {
			Vec3 point = impact.add(new Vec3(random.nextGaussian(), random.nextGaussian() * 0.5, random.nextGaussian()).normalize().scale(spread));
			level.sendParticles(random.nextInt(3) == 0 ? ParticleTypes.WHITE_ASH : ParticleTypes.SMALL_FLAME, point.x, point.y, point.z,
					0, 0, 1, 0, 0.03);
		}
	}

	/** What a block becomes once all the moisture is gone, or null if it stays as it is. */
	private static BlockState dried(BlockState state) {
		// Water and lava, flowing or still
		if (state.liquid() || state.is(Blocks.BUBBLE_COLUMN)) {
			return Blocks.AIR.defaultBlockState();
		}
		if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
			return state.setValue(BlockStateProperties.WATERLOGGED, false);
		}
		// Things that live in water, frozen water and snow simply go
		if (state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT) || state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS)
				|| state.is(Blocks.LILY_PAD) || state.is(Blocks.SUGAR_CANE) || state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE)
				|| state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW)
				|| state.is(Blocks.MOSS_CARPET) || state.is(Blocks.PALE_MOSS_CARPET) || state.is(Blocks.VINE)) {
			return Blocks.AIR.defaultBlockState();
		}
		// Leaves crumble away and leave trees bare
		if (state.is(BlockTags.LEAVES)) {
			return Blocks.AIR.defaultBlockState();
		}
		// Small plants and crops wither (two-tall plants lose their top half along with the bottom)
		if (state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.CROPS) || state.is(Blocks.SHORT_GRASS)
				|| state.is(Blocks.TALL_GRASS) || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN) || state.is(Blocks.BUSH)) {
			return Blocks.DEAD_BUSH.defaultBlockState();
		}
		// Soil cracks
		if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)
				|| state.is(Blocks.FARMLAND) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.MUD)
				|| state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.PALE_MOSS_BLOCK)) {
			return Blocks.COARSE_DIRT.defaultBlockState();
		}
		if (state.is(Blocks.CLAY)) {
			return Blocks.TERRACOTTA.defaultBlockState();
		}
		if (state.is(Blocks.WET_SPONGE)) {
			return Blocks.SPONGE.defaultBlockState();
		}
		if (state.is(Blocks.WATER_CAULDRON) || state.is(Blocks.LAVA_CAULDRON) || state.is(Blocks.POWDER_SNOW_CAULDRON)) {
			return Blocks.CAULDRON.defaultBlockState();
		}
		return null;
	}
}
