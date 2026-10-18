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
 * The upgraded Hourglass of Stillness: right-click to wrap yourself in a bubble of stopped
 * time that moves with you and never runs out. Right-click again to let time go. There's no
 * recharge, only a brief pause between toggles so a double-click doesn't undo itself.
 */
public class HourglassOfEternityItem extends Item {
	private static final int TOGGLE_COOLDOWN = 20;

	public HourglassOfEternityItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level instanceof ServerLevel server && player instanceof ServerPlayer serverPlayer) {
			if (TimeBubble.isActive(player.getUUID())) {
				TimeBubble.stop(player.getUUID());
			} else {
				TimeBubble.start(server, serverPlayer, true);
			}
		}
		player.getCooldowns().addCooldown(player.getItemInHand(hand), TOGGLE_COOLDOWN);
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.translatable("item.powermod.hourglass_of_eternity.tooltip.freeze").withStyle(ChatFormatting.LIGHT_PURPLE));
		builder.accept(Component.translatable("item.powermod.hourglass_of_eternity.tooltip.resume").withStyle(ChatFormatting.LIGHT_PURPLE));
	}
}
