package com.theca.powermod.item;

import com.theca.powermod.power.Aim;
import com.theca.powermod.power.Shockwave;
import com.theca.powermod.power.Storm;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Hold right-click to raise the hammer and call down a storm, release to slam a pillar of
 * lightning into whatever you're looking at ("Skyfall"). Sneak + right-click for an instant
 * ground-slam shockwave around yourself.
 */
public class StormHammerItem extends Item {
	private static final int MIN_CHARGE_TICKS = 20;
	private static final int FULL_CHARGE_TICKS = 60;
	private static final int SKYFALL_COOLDOWN = 100;
	private static final int SLAM_COOLDOWN = 40;
	// Ticks between storm strikes while charging: speeds up until full charge, then holds
	private static final int FIRST_STRIKE_INTERVAL = 16;
	private static final int FASTEST_STRIKE_INTERVAL = 3;

	public StormHammerItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (player.isShiftKeyDown()) {
			if (level instanceof ServerLevel server && player instanceof ServerPlayer serverPlayer) {
				groundSlam(server, serverPlayer);
				stack.hurtAndBreak(1, player, hand);
			}
			player.getCooldowns().addCooldown(stack, SLAM_COOLDOWN);
			return InteractionResult.SUCCESS;
		}
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
		int charge = getUseDuration(stack, user) - remainingTicks;
		Vec3 tip = player.position().add(0, player.getBbHeight() + 0.9, 0);
		RandomSource random = player.getRandom();

		// Sparks get pulled in towards the raised hammer, faster as it charges
		int sparks = 1 + Math.min(charge, FULL_CHARGE_TICKS) / 15;
		for (int i = 0; i < sparks; i++) {
			Vec3 from = tip.add(new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize().scale(3));
			Vec3 toTip = tip.subtract(from).normalize();
			server.sendParticles(ParticleTypes.ELECTRIC_SPARK, from.x, from.y, from.z, 0, toTip.x, toTip.y, toTip.z, 0.35);
		}

		if (charge == 1) {
			server.playSound(null, player.blockPosition(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 1f, 1.4f);
		}
		// The storm lashes the ground around you for as long as the hammer is raised
		if ((int) strikePhase(charge) > (int) strikePhase(charge - 1)) {
			double angle = random.nextDouble() * Math.PI * 2;
			double distance = 5 + random.nextDouble() * 5;
			int x = Mth.floor(player.getX() + Math.cos(angle) * distance);
			int z = Mth.floor(player.getZ() + Math.sin(angle) * distance);
			BlockPos ground = Shockwave.findSurface(server, x, z, player.getBlockY());
			double y = ground != null ? ground.getY() + 1 : player.getY();
			Storm.strike(server, new Vec3(x + 0.5, y, z + 0.5), player);
		}
		if (charge == FULL_CHARGE_TICKS) {
			server.playSound(null, player.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.2f, 1.6f);
			server.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF), tip.x, tip.y, tip.z, 1, 0, 0, 0, 0);
		}
	}

	@Override
	public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remainingTicks) {
		if (!(level instanceof ServerLevel server) || !(user instanceof ServerPlayer player)) {
			return false;
		}
		int charge = getUseDuration(stack, user) - remainingTicks;
		if (charge < MIN_CHARGE_TICKS) {
			return false;
		}
		float power = Math.min(charge, FULL_CHARGE_TICKS) / (float) FULL_CHARGE_TICKS;
		skyfall(server, player, Aim.target(server, player), power);
		player.getCooldowns().addCooldown(stack, SKYFALL_COOLDOWN);
		InteractionHand hand = player.getMainHandItem() == stack ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
		stack.hurtAndBreak(3, player, hand);
		return true;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.translatable("item.powermod.storm_hammer.tooltip.skyfall").withStyle(ChatFormatting.AQUA));
		builder.accept(Component.translatable("item.powermod.storm_hammer.tooltip.shockwave").withStyle(ChatFormatting.AQUA));
	}

	private static double strikeInterval(int charge) {
		float progress = Math.min(charge, FULL_CHARGE_TICKS) / (float) FULL_CHARGE_TICKS;
		return Mth.lerp(progress, FIRST_STRIKE_INTERVAL, FASTEST_STRIKE_INTERVAL);
	}

	/** How many strikes are due after {@code charge} ticks; a strike lands each time this passes a whole number. */
	private static double strikePhase(int charge) {
		double phase = 0;
		for (int t = 0; t < Math.min(charge, FULL_CHARGE_TICKS); t++) {
			phase += 1 / strikeInterval(t);
		}
		if (charge > FULL_CHARGE_TICKS) {
			phase += (charge - FULL_CHARGE_TICKS) / (double) FASTEST_STRIKE_INTERVAL;
		}
		return phase;
	}

	private static void skyfall(ServerLevel level, ServerPlayer player, Vec3 target, float power) {
		RandomSource random = player.getRandom();

		Storm.strike(level, target, player);
		int extraBolts = 4 + Math.round(8 * power);
		for (int i = 0; i < extraBolts; i++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double distance = 3 + random.nextDouble() * 7;
			Storm.strike(level, target.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance), player);
		}

		// Pillar of light from the sky down to the impact
		double pillarWidth = 0.8 + 1.2 * power;
		for (double dy = 0; dy < 60; dy += 0.5) {
			level.sendParticles(ParticleTypes.END_ROD, target.x, target.y + dy, target.z, 4, pillarWidth, 0.2, pillarWidth, 0.02);
			level.sendParticles(Shockwave.ENERGY, target.x, target.y + dy, target.z, 2, pillarWidth * 1.3, 0.2, pillarWidth * 1.3, 0);
		}
		level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF), target.x, target.y + 1, target.z, 6, 2, 1, 2, 0);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, target.x, target.y + 0.5, target.z, 3 + Math.round(5 * power), 3, 1, 3, 0);
		level.sendParticles(ParticleTypes.EXPLOSION, target.x, target.y + 0.5, target.z, 30, 6, 1.5, 6, 0);
		level.playSound(null, target.x, target.y, target.z, SoundEvents.TRIDENT_THUNDER, SoundSource.PLAYERS, 4f, 0.8f);
		level.playSound(null, target.x, target.y, target.z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 3f, 0.6f);
		level.playSound(null, target.x, target.y, target.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 4f, 0.6f);

		float impactDamage = 12 + 8 * power;
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, new AABB(target, target).inflate(4 + 4 * power))) {
			if (victim != player) {
				victim.hurtServer(level, level.damageSources().playerAttack(player), impactDamage);
			}
		}

		// Small crater at the point of impact
		float craterRadius = 2.5f + 2.5f * power;
		BlockPos centre = BlockPos.containing(target);
		int r = Mth.ceil(craterRadius);
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, 0, r))) {
			if (pos.distToCenterSqr(target) <= craterRadius * craterRadius) {
				Shockwave.tryBreak(level, player, pos.immutable(), 0.6f);
			}
		}

		Shockwave.start(level, player, target, 16 + 24 * power, 6 + 6 * power, 0.3f + 0.15f * power);
	}

	private static void groundSlam(ServerLevel level, ServerPlayer player) {
		Vec3 feet = player.position();
		level.playSound(null, player.blockPosition(), SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1.5f, 0.8f);
		level.playSound(null, player.blockPosition(), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 1f, 1.2f);
		level.playSound(null, feet.x, feet.y, feet.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 2f, 0.8f);
		level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF), feet.x, feet.y + 0.2, feet.z, 3, 1, 0.2, 1, 0);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, feet.x, feet.y + 0.5, feet.z, 2, 2, 0.3, 2, 0);
		Shockwave.start(level, player, feet, 20, 7);
	}
}
