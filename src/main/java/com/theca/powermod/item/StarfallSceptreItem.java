package com.theca.powermod.item;

import com.theca.powermod.power.Aim;
import com.theca.powermod.power.Meteor;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
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
 * Hold right-click to mark a spot; the longer you hold, the bigger the area and the more
 * meteors. Let go and they rain down on it one after another. The sceptre draws on the
 * stars, so it is at full strength at night (and in dimensions without a day) and much
 * weaker in daylight.
 */
public class StarfallSceptreItem extends Item {
	private static final int MIN_CHARGE_TICKS = 10;
	private static final int FULL_CHARGE_TICKS = 80;
	private static final int COOLDOWN = 100;
	private static final DustParticleOptions MARKER = new DustParticleOptions(0xFF7A2E, 1.5f);

	public StarfallSceptreItem(Properties properties) {
		super(properties);
	}

	private static boolean starsOut(ServerLevel level) {
		return level.dimensionType().hasFixedTime() || level.isDarkOutside();
	}

	private static float charge(int ticks) {
		return Math.min(ticks, FULL_CHARGE_TICKS) / (float) FULL_CHARGE_TICKS;
	}

	private static double spread(float charge) {
		return 3 + 7 * charge;
	}

	/** 2 meteors at minimum charge up to 12 at full; half as many in daylight. */
	private static int meteorCount(float charge, boolean night) {
		int count = 2 + Math.round(10 * charge);
		return night ? count : Math.max(1, count / 2);
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
		float charge = charge(ticks);
		Vec3 target = Aim.target(server, player);
		double spread = spread(charge);

		// A slowly turning ring of embers marks where the meteors will fall
		int points = Mth.ceil(spread * 4);
		double turn = ticks * 0.05;
		for (int i = 0; i < points; i++) {
			double angle = turn + Math.PI * 2 * i / points;
			server.sendParticles(MARKER, true, false, target.x + Math.cos(angle) * spread, target.y + 0.2, target.z + Math.sin(angle) * spread,
					1, 0, 0, 0, 0);
		}
		server.sendParticles(ParticleTypes.FLAME, true, false, target.x, target.y + 0.2, target.z, 2, 0.3, 0, 0.3, 0.01);

		if (ticks == 1) {
			server.playSound(null, player.blockPosition(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 1f, 0.6f);
		}
		if (ticks == FULL_CHARGE_TICKS) {
			server.playSound(null, player.blockPosition(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 1.5f, 1.4f);
		}
		if (ticks % 5 == 0) {
			boolean night = starsOut(server);
			Component message = Component.translatable("item.powermod.starfall_sceptre.charge", meteorCount(charge, night));
			if (!night) {
				message = Component.translatable("item.powermod.starfall_sceptre.daylight", message);
			}
			player.sendOverlayMessage(message.copy().withStyle(night ? ChatFormatting.GOLD : ChatFormatting.GRAY));
		}
	}

	@Override
	public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remainingTicks) {
		if (!(level instanceof ServerLevel server) || !(user instanceof ServerPlayer player)) {
			return false;
		}
		int ticks = getUseDuration(stack, user) - remainingTicks;
		if (ticks < MIN_CHARGE_TICKS) {
			return false;
		}
		float charge = charge(ticks);
		boolean night = starsOut(server);
		// Each meteor hits about as hard as TNT at night, much softer by day
		float power = (3 + 1.5f * charge) * (night ? 1 : 0.6f);
		Meteor.barrage(server, player, Aim.target(server, player), meteorCount(charge, night), spread(charge), power);
		server.playSound(null, player.blockPosition(), SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.PLAYERS, 1.5f, 0.6f);
		player.getCooldowns().addCooldown(stack, COOLDOWN);
		return true;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.translatable("item.powermod.starfall_sceptre.tooltip.call").withStyle(ChatFormatting.GOLD));
		builder.accept(Component.translatable("item.powermod.starfall_sceptre.tooltip.night").withStyle(ChatFormatting.GOLD));
	}
}
