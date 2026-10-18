package com.theca.powermod.item;

import com.theca.powermod.power.TimeBubble;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * Right-click to stop time in a bubble around where you stand (see TimeBubble).
 * Right-click again to let time go early. The hourglass recharges after the bubble ends.
 */
public class HourglassOfStillnessItem extends Item {
	public static final int COOLDOWN = 200;
	private static final int START_COOLDOWN = 20;

	public HourglassOfStillnessItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level instanceof ServerLevel server && player instanceof ServerPlayer serverPlayer) {
			if (TimeBubble.isActive(player.getUUID())) {
				TimeBubble.stop(player.getUUID());
			} else {
				TimeBubble.start(server, serverPlayer, false);
				// A brief pause so a double-click doesn't end the bubble straight away
				player.getCooldowns().addCooldown(player.getItemInHand(hand), START_COOLDOWN);
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.translatable("item.powermod.hourglass_of_stillness.tooltip.freeze").withStyle(ChatFormatting.LIGHT_PURPLE));
		builder.accept(Component.translatable("item.powermod.hourglass_of_stillness.tooltip.resume").withStyle(ChatFormatting.LIGHT_PURPLE));
	}
}
