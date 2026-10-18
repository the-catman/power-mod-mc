package com.theca.powermod.item;

import com.theca.powermod.mixin.ServerGamePacketListenerImplAccessor;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * Lets the holder ride the wind (in either hand, no input needed to keep it active).
 * The flight itself runs on the client, since players control their own movement;
 * see WindRider in the client source set. The server side only keeps the holder from
 * taking fall damage or being kicked for floating.
 */
public class FlightStaffItem extends Item {
	private static final int DASH_COOLDOWN = 30;

	// Set by the client so a right-click can launch the local player's dash
	public static Consumer<Player> clientDash = player -> {
	};

	public FlightStaffItem(Properties properties) {
		super(properties);
	}

	public static boolean isHolding(Player player) {
		return player.getMainHandItem().getItem() instanceof FlightStaffItem
				|| player.getOffhandItem().getItem() instanceof FlightStaffItem;
	}

	public static void registerEvents() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (isHolding(player)) {
					player.resetFallDistance();
					((ServerGamePacketListenerImplAccessor) player.connection).powermod$setAboveGroundTickCount(0);
				}
			}
		});
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) {
			clientDash.accept(player);
		} else if (level instanceof ServerLevel server) {
			server.playSound(null, player.blockPosition(), SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1f, 1.2f);
			server.sendParticles(ParticleTypes.GUST, player.getX(), player.getY() + 0.5, player.getZ(), 1, 0, 0, 0, 0);
		}
		player.getCooldowns().addCooldown(player.getItemInHand(hand), DASH_COOLDOWN);
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.translatable("item.powermod.staff_of_flight.tooltip.takeoff").withStyle(ChatFormatting.AQUA));
		builder.accept(Component.translatable("item.powermod.staff_of_flight.tooltip.fly").withStyle(ChatFormatting.AQUA));
		builder.accept(Component.translatable("item.powermod.staff_of_flight.tooltip.dash").withStyle(ChatFormatting.AQUA));
	}
}
