package com.theca.powermod.client;

import net.fabricmc.api.ClientModInitializer;

public class PowerModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		WindRider.register();
	}
}
