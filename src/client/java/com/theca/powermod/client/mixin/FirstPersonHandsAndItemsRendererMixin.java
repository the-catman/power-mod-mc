package com.theca.powermod.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.theca.powermod.item.AncientWindsStaffItem;
import com.theca.powermod.item.ModComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class FirstPersonHandsAndItemsRendererMixin {
	/**
	 * A Staff of Ancient Winds channeling hands-free from the offhand isn't actually being used,
	 * so vanilla draws it held normally. This applies the same raised, quivering trident pose
	 * vanilla uses while the staff is held on right-click, right before the item is drawn.
	 */
	@Inject(method = "submitArmWithItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V",
			ordinal = 1))
	private void powermod$channelPose(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float a, float b,
			InteractionHand hand, float c, ItemStack stack, float d, PoseStack poseStack, SubmitNodeCollector collector, int light,
			CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		Long start = stack.get(ModComponents.CHANNEL_START);
		if (player == null || start == null || hand != InteractionHand.OFF_HAND || !(stack.getItem() instanceof AncientWindsStaffItem)) {
			return;
		}
		HumanoidArm arm = player.getMainArm().getOpposite();
		int side = arm == HumanoidArm.RIGHT ? 1 : -1;
		float ticks = player.level().getGameTime() - start + minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);

		// Matches vanilla's fully charged trident pose
		poseStack.translate(side * -0.5f, 0.7f, 0.1f);
		poseStack.rotateDegrees(Axis.XP, -55f);
		poseStack.rotateDegrees(Axis.YP, side * 35.3f);
		poseStack.rotateDegrees(Axis.ZP, side * -9.785f);
		float shake = Mth.sin((ticks - 0.1f) * 1.3f) * 0.9f;
		poseStack.translate(0, shake * 0.004f, 0);
		poseStack.translate(0, 0, 0.2f);
		poseStack.scale(1, 1, 1.2f);
		poseStack.rotateDegrees(Axis.YN, side * 45f);
	}
}
