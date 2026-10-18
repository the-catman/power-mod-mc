package com.theca.powermod.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerImplAccessor {
	// Resetting this every tick keeps wind riders from being kicked for floating
	@Accessor("aboveGroundTickCount")
	void powermod$setAboveGroundTickCount(int ticks);
}
