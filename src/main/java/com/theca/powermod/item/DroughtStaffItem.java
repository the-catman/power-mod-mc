package com.theca.powermod.item;

import com.theca.powermod.power.Aim;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Hold right-click to draw every drop of moisture out of the area you're looking at, in a
 * slowly widening sphere. Water and lava vanish, waterlogged blocks dry out, plants wither
 * into dead bushes, leaves crumble away, soil cracks into coarse dirt, ice and snow are gone.
 * Creatures caught in it grow parched, and anything that lives in water suffocates.
 */
public class DroughtStaffItem extends Item {
	private static final double MAX_RADIUS = 16;
	// Spots checked per tick at radius 3; scales with the sphere's volume so bigger areas dry just as thoroughly
	private static final int BASE_SAMPLES_PER_TICK = 60;
	private static final int MAX_SAMPLES_PER_TICK = 2500;
	private static final int RELEASE_COOLDOWN = 20;

	public DroughtStaffItem(Properties properties) {
		super(properties);
	}

	/** 3 blocks at first, ~10 after 12 seconds, capped at 16 (~42 seconds). */
	private static double radius(float seconds) {
		return Math.min(MAX_RADIUS, 3 + 2 * Math.sqrt(seconds));
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		player.startUsingItem(hand);
		return InteractionResult.CONSUME;
	}

	@Override
	public ItemUseAnimation getUseAnimation(ItemStack stack) {
		return ItemUseAnimation.TRIDENT;
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity user) {
		return 72000;
	}

	@Override
	public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remainingTicks) {
		if (!(level instanceof ServerLevel server) || !(user instanceof ServerPlayer player)) {
			return;
		}
		int ticks = getUseDuration(stack, user) - remainingTicks;
		float seconds = ticks / 20f;
		double radius = radius(seconds);
		// Water and lava are what this staff is for, so aim at their surface rather than through them
		Vec3 focus = Aim.target(server, player, true);

		int dried = parch(server, player, focus, radius, 40 + (int) Math.min(110, seconds * 4));
		parchCreatures(server, player, focus, radius, ticks);
		heatHaze(server, player, focus, radius);

		// One sizzle every few ticks stands in for everything drying out, so the sound pool doesn't fill up
		if (dried > 0 && ticks % 4 == 0) {
			server.playSound(null, focus.x, focus.y, focus.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 1.5f, 0.6f);
		}
		if (ticks % 30 == 0) {
			server.playSound(null, focus.x, focus.y, focus.z, SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 2f, 0.5f);
		}
		ambience(server, player, focus, ticks);
		if (ticks % 10 == 0) {
			player.sendOverlayMessage(Component.translatable("item.powermod.staff_of_drought.channel", (int) seconds, (int) radius)
					.withStyle(ChatFormatting.GOLD));
		}
	}

	// A dry desert wind over the parched area and a low heat hum around you, for as long as it's active
	private static void ambience(ServerLevel level, ServerPlayer player, Vec3 focus, int ticks) {
		if (ticks == 1) {
			level.playSound(null, player.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.8f, 0.5f);
		}
		if (ticks % 20 == 1) {
			level.playSound(null, focus.x, focus.y, focus.z, SoundEvents.DEAD_BUSH_IDLE, SoundSource.PLAYERS, 3f, 0.8f);
		}
		if (ticks % 40 == 11) {
			level.playSound(null, focus.x, focus.y, focus.z, SoundEvents.SAND_IDLE, SoundSource.PLAYERS, 3f, 0.7f);
		}
		if (ticks % 25 == 5) {
			level.playSound(null, player.blockPosition(), SoundEvents.BLAZE_BURN, SoundSource.PLAYERS, 0.7f, 0.5f);
		}
	}

	@Override
	public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remainingTicks) {
		if (user instanceof Player player) {
			player.getCooldowns().addCooldown(stack, RELEASE_COOLDOWN);
		}
		return true;
	}

	/** Dries out random spots in the sphere, up to {@code budget} blocks. Returns how many changed. */
	private static int parch(ServerLevel level, ServerPlayer player, Vec3 centre, double radius, int budget) {
		RandomSource random = player.getRandom();
		int changed = 0;
		int samples = (int) Math.min(MAX_SAMPLES_PER_TICK, BASE_SAMPLES_PER_TICK * Math.pow(radius / 3, 3));
		for (int i = 0; i < samples && changed < budget; i++) {
			// Uniformly random point in the sphere
			Vec3 direction = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize();
			BlockPos pos = BlockPos.containing(centre.add(direction.scale(radius * Math.cbrt(random.nextDouble()))));
			if (!level.isLoaded(pos) || !level.mayInteract(player, pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			BlockState dry = dried(state);
			if (dry == null) {
				continue;
			}
			level.setBlock(pos, dry, Block.UPDATE_ALL);
			// Steam off water, smoke off lava, dust off everything else
			double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
			if (state.getFluidState().is(FluidTags.LAVA)) {
				level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 1, 0.3, 0.3, 0.3, 0.02);
			} else if (!state.getFluidState().isEmpty() || state.is(Blocks.ICE) || state.is(Blocks.SNOW_BLOCK)) {
				level.sendParticles(ParticleTypes.CLOUD, x, y, z, 1, 0.3, 0.3, 0.3, 0.03);
			} else {
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), x, y, z, 3, 0.3, 0.3, 0.3, 0.05);
			}
			changed++;
		}
		return changed;
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

	// Creatures in the area grow hungry and parched; water creatures dry out and suffocate
	private static void parchCreatures(ServerLevel level, ServerPlayer player, Vec3 centre, double radius, int ticks) {
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(radius))) {
			if (entity == player || entity.isSpectator() || entity.distanceToSqr(centre) > radius * radius
					|| entity instanceof OwnableEntity pet && pet.getOwner() == player) {
				continue;
			}
			entity.addEffect(new MobEffectInstance(MobEffects.HUNGER, 60, 2), player);
			if (entity instanceof WaterAnimal && ticks % 10 == 0) {
				entity.hurtServer(level, level.damageSources().dryOut(), 2);
			}
		}
	}

	// Shimmering heat and drifting dust rising off the parched area
	private static void heatHaze(ServerLevel level, ServerPlayer player, Vec3 centre, double radius) {
		RandomSource random = player.getRandom();
		int count = 4 + (int) radius;
		for (int i = 0; i < count; i++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double distance = Math.sqrt(random.nextDouble()) * radius;
			double x = centre.x + Math.cos(angle) * distance;
			double z = centre.z + Math.sin(angle) * distance;
			double y = centre.y + (random.nextDouble() - 0.3) * radius * 0.5;
			level.sendParticles(random.nextInt(3) == 0 ? ParticleTypes.WHITE_ASH : ParticleTypes.SMALL_FLAME, x, y, z, 0, 0, 1, 0, 0.03);
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.translatable("item.powermod.staff_of_drought.tooltip.channel").withStyle(ChatFormatting.GOLD));
		builder.accept(Component.translatable("item.powermod.staff_of_drought.tooltip.wither").withStyle(ChatFormatting.GOLD));
	}
}
