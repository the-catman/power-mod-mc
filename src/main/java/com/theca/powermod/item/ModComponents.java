package com.theca.powermod.item;

import com.theca.powermod.PowerMod;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;

public final class ModComponents {
	// Game time a hands-free Staff of Ancient Winds channel began; lets clients show the channeling pose in the offhand.
	// Not saved to disk, so a channel cut short by a restart never leaves a stale marker behind.
	public static final DataComponentType<Long> CHANNEL_START = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
			PowerMod.id("channel_start"),
			DataComponentType.<Long>builder().networkSynchronized(ByteBufCodecs.VAR_LONG).ignoreSwapAnimation().build());

	private ModComponents() {
	}

	// Called from PowerMod to force static initialization.
	public static void initialize() {
	}
}
