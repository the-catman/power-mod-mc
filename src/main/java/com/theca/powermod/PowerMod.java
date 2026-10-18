package com.theca.powermod;

import com.theca.powermod.item.AncientWindsStaffItem;
import com.theca.powermod.item.FlightStaffItem;
import com.theca.powermod.item.ModComponents;
import com.theca.powermod.item.ModItems;
import com.theca.powermod.power.Cataclysm;
import com.theca.powermod.power.Meteor;
import com.theca.powermod.power.Shockwave;
import com.theca.powermod.power.Storm;
import com.theca.powermod.power.TimeBubble;
import com.theca.powermod.power.WindGust;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PowerMod implements ModInitializer {
	public static final String MOD_ID = "powermod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModComponents.initialize();
		ModItems.initialize();
		AncientWindsStaffItem.registerEvents();
		FlightStaffItem.registerEvents();
		Storm.register();
		Shockwave.register();
		WindGust.register();
		Meteor.register();
		Cataclysm.register();
		TimeBubble.register();
		LOGGER.info("Power Mod loaded");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
