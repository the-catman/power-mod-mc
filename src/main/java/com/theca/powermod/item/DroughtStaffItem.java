package com.theca.powermod.item;

import com.theca.powermod.power.DroughtBolt;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import net.minecraft.world.phys.Vec3;

/**
 * Hold right-click to draw heat into the staff, release to fire it like a bow (see DroughtBolt).
 * The longer you charge, up to a limit, the faster the orb flies and the wider the drought
 * it leaves where it lands.
 */
public class DroughtStaffItem extends Item {
	private static final int MIN_CHARGE_TICKS = 5;
	private static final int FULL_CHARGE_TICKS = 60;
	private static final double MIN_RADIUS = 3;
	private static final double MAX_RADIUS = 10;
	private static final int COOLDOWN = 30;

	public DroughtStaffItem(Properties properties) {
		super(properties);
	}

	private static float power(int charge) {
		return Math.min(charge, FULL_CHARGE_TICKS) / (float) FULL_CHARGE_TICKS;
	}

	private static double radius(float power) {
		return MIN_RADIUS + (MAX_RADIUS - MIN_RADIUS) * power;
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
		int charge = getUseDuration(stack, user) - remainingTicks;
		float power = power(charge);

		// Heat and dust drawn in towards the staff, thicker as it charges
		RandomSource random = player.getRandom();
		Vec3 tip = player.getEyePosition().add(player.getLookAngle().scale(1.2));
		for (int i = 0; i < 1 + Math.round(4 * power); i++) {
			Vec3 from = tip.add(new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize().scale(2));
			Vec3 toTip = tip.subtract(from).normalize();
			server.sendParticles(random.nextBoolean() ? ParticleTypes.SMALL_FLAME : ParticleTypes.WHITE_ASH,
					from.x, from.y, from.z, 0, toTip.x, toTip.y, toTip.z, 0.15);
		}
		ambience(server, player, charge);
		if (charge == FULL_CHARGE_TICKS) {
			server.playSound(null, player.blockPosition(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1f, 0.6f);
		}
		if (charge % 4 == 0) {
			player.sendOverlayMessage(Component.translatable("item.powermod.staff_of_drought.charge",
					Math.round(power * 100), (int) radius(power)).withStyle(charge < MIN_CHARGE_TICKS ? ChatFormatting.GRAY : ChatFormatting.GOLD));
		}
	}

	// A dry desert wind and a low heat hum around you while the staff draws in heat
	private static void ambience(ServerLevel level, ServerPlayer player, int charge) {
		if (charge == 1) {
			level.playSound(null, player.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.6f, 0.5f);
		}
		if (charge % 20 == 1) {
			level.playSound(null, player.blockPosition(), SoundEvents.DEAD_BUSH_IDLE, SoundSource.PLAYERS, 1.5f, 0.8f);
		}
		if (charge % 40 == 11) {
			level.playSound(null, player.blockPosition(), SoundEvents.SAND_IDLE, SoundSource.PLAYERS, 1.5f, 0.7f);
		}
		if (charge % 25 == 5) {
			level.playSound(null, player.blockPosition(), SoundEvents.BLAZE_BURN, SoundSource.PLAYERS, 0.7f, 0.5f);
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
		float power = power(charge);
		DroughtBolt.fire(server, player, power, radius(power));
		server.playSound(null, player.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1f, 0.7f + 0.3f * power);
		player.getCooldowns().addCooldown(stack, COOLDOWN);
		return true;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.translatable("item.powermod.staff_of_drought.tooltip.channel").withStyle(ChatFormatting.GOLD));
		builder.accept(Component.translatable("item.powermod.staff_of_drought.tooltip.wither").withStyle(ChatFormatting.GOLD));
	}
}
