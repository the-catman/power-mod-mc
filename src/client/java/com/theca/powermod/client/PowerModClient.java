package com.theca.powermod.client;

import com.theca.powermod.power.TimeBubble;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class PowerModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		WindRider.register();
		ClientPlayNetworking.registerGlobalReceiver(TimeBubble.SyncPayload.TYPE, (payload, context) -> TimeBubble.setClientZones(payload.zones()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TimeBubble.setClientZones(List.of()));
	}
}
