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

public final class ModItems {
	// No melee bonus and no durability: its power is all in Skyfall and the shockwave
	public static final StormHammerItem STORM_HAMMER = register("storm_hammer", StormHammerItem::new,
			new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant());

	public static final AncientWindsStaffItem STAFF_OF_ANCIENT_WINDS = register("staff_of_ancient_winds", AncientWindsStaffItem::new,
			new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant());

	public static final FlightStaffItem STAFF_OF_FLIGHT = register("staff_of_flight", FlightStaffItem::new,
			new Item.Properties().stacksTo(1).rarity(Rarity.RARE));

	public static final StarfallSceptreItem STARFALL_SCEPTRE = register("starfall_sceptre", StarfallSceptreItem::new,
			new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant());

	public static final HourglassOfStillnessItem HOURGLASS_OF_STILLNESS = register("hourglass_of_stillness", HourglassOfStillnessItem::new,
			new Item.Properties().stacksTo(1).rarity(Rarity.RARE));

	public static final HourglassOfEternityItem HOURGLASS_OF_ETERNITY = register("hourglass_of_eternity", HourglassOfEternityItem::new,
			new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant());

	public static final DroughtStaffItem STAFF_OF_DROUGHT = register("staff_of_drought", DroughtStaffItem::new,
			new Item.Properties().stacksTo(1).rarity(Rarity.RARE));

	// The Staff of Drought's crafting chain: arid clay is smelted into sunbaked shards, which make
	// sunbaked rods and, with dried kelp and a bucket, a parched core
	public static final Item ARID_CLAY = register("arid_clay", Item::new, new Item.Properties());
	public static final Item SUNBAKED_SHARD = register("sunbaked_shard", Item::new, new Item.Properties());
	public static final Item SUNBAKED_ROD = register("sunbaked_rod", Item::new, new Item.Properties());
	public static final Item PARCHED_CORE = register("parched_core", Item::new, new Item.Properties().rarity(Rarity.UNCOMMON));

	// Rare ingredient shared by the mod's top-tier items
	public static final Item PRIMORDIAL_CORE = register("primordial_core", Item::new,
			new Item.Properties().rarity(Rarity.RARE).fireResistant());

	// Crafting ingredient for the Hourglass of Stillness
	public static final Item ESSENCE_OF_TIME = register("essence_of_time", Item::new,
			new Item.Properties().rarity(Rarity.RARE));

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
						output.accept(STARFALL_SCEPTRE);
						output.accept(HOURGLASS_OF_STILLNESS);
						output.accept(HOURGLASS_OF_ETERNITY);
						output.accept(STAFF_OF_DROUGHT);
						output.accept(PRIMORDIAL_CORE);
						output.accept(ZEPHYR_FEATHER);
						output.accept(ESSENCE_OF_TIME);
						output.accept(ARID_CLAY);
						output.accept(SUNBAKED_SHARD);
						output.accept(SUNBAKED_ROD);
						output.accept(PARCHED_CORE);
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
