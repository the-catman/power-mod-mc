package com.theca.powermod.item;

import com.theca.powermod.PowerMod;
import java.util.function.Function;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;

public final class ModItems {
	// Diamond-tier sword stats with +10 damage: 14 attack damage, 0.8 attack speed
	public static final StormHammerItem STORM_HAMMER = register("storm_hammer", StormHammerItem::new,
			new Item.Properties().sword(ToolMaterial.DIAMOND, 10f, -3.2f).rarity(Rarity.EPIC).fireResistant());

	public static final AncientWindsStaffItem STAFF_OF_ANCIENT_WINDS = register("staff_of_ancient_winds", AncientWindsStaffItem::new,
			new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant());

	public static final FlightStaffItem STAFF_OF_FLIGHT = register("staff_of_flight", FlightStaffItem::new,
			new Item.Properties().stacksTo(1).rarity(Rarity.RARE));

	// Crafting ingredient for the Staff of Flight
	public static final Item ZEPHYR_FEATHER = register("zephyr_feather", Item::new,
			new Item.Properties().rarity(Rarity.UNCOMMON));

	public static final ResourceKey<CreativeModeTab> TAB_KEY =
			ResourceKey.create(Registries.CREATIVE_MODE_TAB, PowerMod.id("items"));

	public static final CreativeModeTab TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB_KEY,
			FabricCreativeModeTab.builder()
					.title(Component.translatable("itemGroup.powermod.items"))
					.icon(() -> new ItemStack(STORM_HAMMER))
					.displayItems((params, output) -> {
						output.accept(STORM_HAMMER);
						output.accept(STAFF_OF_ANCIENT_WINDS);
						output.accept(STAFF_OF_FLIGHT);
						output.accept(ZEPHYR_FEATHER);
					})
					.build());

	private ModItems() {
	}

	public static <T extends Item> T register(String name, Function<Item.Properties, T> factory, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, PowerMod.id(name));
		T item = factory.apply(properties.setId(key));
		return Registry.register(BuiltInRegistries.ITEM, key, item);
	}

	// Called from PowerMod to force static initialization.
	public static void initialize() {
	}
}
