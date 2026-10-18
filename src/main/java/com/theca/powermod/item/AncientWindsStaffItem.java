package com.theca.powermod.item;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import com.theca.powermod.power.Aim;
import com.theca.powermod.power.Debris;
import com.theca.powermod.power.WindGust;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Hold right-click to channel a whirlwind wherever you're looking; it follows your aim.
 * The whirlwind is fed by gusts streaming out from around you (see WindGust): each one
 * takes time to fly there, and tears at the blocks and shoves the creatures where it lands.
 * It never stops building: the longer you channel, the more gusts, the wider it reaches and
 * the harder the blocks it can tear away, until eventually only unbreakable blocks survive.
 * After 5 seconds the staff moves itself to your offhand and keeps channeling hands-free.
 * Letting go of right-click, or taking the staff out of your offhand, ends it with a final
 * gust that hurls everything around the whirlwind away.
 */
public class AncientWindsStaffItem extends Item {
	// Blocks this close to the wielder are left alone, even if you aim at your own feet
	private static final double CALM_RADIUS = 3;
	private static final double MAX_RADIUS = 25;
	// Spots checked per gust landing, per cubic block of the area it lands in
	private static final double SAMPLES_PER_BLOCK = 1.5;
	private static final int MAX_SAMPLES_PER_GUST = 1500;
	// Each gust works on this share of the whirlwind's radius around where it lands
	private static final double GUST_REACH = 0.6;
	private static final double MIN_GUST_REACH = 3;
	private static final float MAX_GUSTS_PER_TICK = 3;
	// Damage from each gust that lands on a creature, growing as the wind builds. Mobs can only
	// be hurt every half second, so this is roughly half the damage per second.
	private static final float MIN_GUST_DAMAGE = 2;
	private static final float MAX_GUST_DAMAGE = 8;
	// Damage from the final blast at its centre, falling off to half at its edge
	private static final float MIN_BLAST_DAMAGE = 6;
	private static final float MAX_BLAST_DAMAGE = 24;
	private static final int MIN_RELEASE_TICKS = 10;
	private static final int RELEASE_COOLDOWN = 60;
	private static final int AUTO_OFFHAND_TICKS = 100;
	// Chance a torn-away block is flung as debris instead of vanishing, capped per tick
	private static final float DEBRIS_CHANCE = 0.25f;
	private static final int MAX_DEBRIS_PER_TICK = 2;
	// Players channeling hands-free from the offhand, and how many ticks they've channeled
	private static final Map<UUID, Integer> SUSTAINED = new HashMap<>();

	public AncientWindsStaffItem(Properties properties) {
		super(properties);
	}

	/** Seconds channeled; grows without limit. */
	private static float strength(int ticks) {
		return ticks / 20f;
	}

	/** Reach grows with the square root of strength: 5 blocks at first, ~20 after a minute, capped at 25 (~100s). */
	private static double radius(float strength) {
		return Math.min(MAX_RADIUS, 5 + 2 * Math.sqrt(strength));
	}

	/**
	 * Hardest block the wind can break, doubling every 10 seconds: dirt and leaves at once,
	 * stone ~16s, ores ~26s, metal blocks ~33s, obsidian ~66s.
	 */
	private static float maxHardness(float strength) {
		return 0.5f * (float) Math.pow(2, strength / 10);
	}

	private static String tier(float maxHardness) {
		if (maxHardness < 1.5f) return "breeze";
		if (maxHardness < 3f) return "gale";
		if (maxHardness < 5f) return "storm";
		if (maxHardness < 50f) return "tempest";
		return "cataclysm";
	}

	public static void registerEvents() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.isUsingItem() && player.getUseItem().getItem() instanceof AncientWindsStaffItem
						&& player.getTicksUsingItem() >= AUTO_OFFHAND_TICKS && !SUSTAINED.containsKey(player.getUUID())) {
					moveToOffhand(player);
				}
			}
			SUSTAINED.entrySet().removeIf(entry -> !tickSustained(server.getPlayerList().getPlayer(entry.getKey()), entry));
		});
	}

	private static void moveToOffhand(ServerPlayer player) {
		int ticks = player.getTicksUsingItem();
		InteractionHand hand = player.getUsedItemHand();
		ItemStack staff = player.getItemInHand(hand);
		player.stopUsingItem();
		if (hand == InteractionHand.MAIN_HAND) {
			ItemStack offhand = player.getOffhandItem();
			player.setItemInHand(InteractionHand.MAIN_HAND, offhand);
			player.setItemInHand(InteractionHand.OFF_HAND, staff);
		}
		staff.set(ModComponents.CHANNEL_START, player.level().getGameTime() - ticks);
		SUSTAINED.put(player.getUUID(), ticks);
		// Cue that the staff is now channeling on its own and right-click can be let go
		player.level().playSound(null, player.blockPosition(), SoundEvents.BREEZE_INHALE, SoundSource.PLAYERS, 2f, 0.7f);
	}

	/** Runs one tick of a hands-free channel. Returns false once it has ended. */
	private static boolean tickSustained(ServerPlayer player, Map.Entry<UUID, Integer> entry) {
		if (player == null || !player.isAlive() || !(player.level() instanceof ServerLevel server)) {
			return false;
		}
		int ticks = entry.getValue() + 1;
		entry.setValue(ticks);
		if (!(player.getOffhandItem().getItem() instanceof AncientWindsStaffItem)) {
			releaseGust(server, player, ticks);
			player.getCooldowns().addCooldown(ModItems.STAFF_OF_ANCIENT_WINDS.getDefaultInstance(), RELEASE_COOLDOWN);
			return false;
		}
		channelTick(server, player, ticks);
		return true;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (SUSTAINED.containsKey(player.getUUID())) {
			return InteractionResult.FAIL;
		}
		player.startUsingItem(hand);
		return InteractionResult.CONSUME;
	}

	@Override
	public ItemUseAnimation getUseAnimation(ItemStack stack) {
		return ItemUseAnimation.TRIDENT;
	}

	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
		// Clear the channel marker once the staff stops channeling hands-free (swapped out, or the channel ended)
		if (stack.has(ModComponents.CHANNEL_START)
				&& (slot != EquipmentSlot.OFFHAND || !SUSTAINED.containsKey(owner.getUUID()))) {
			stack.remove(ModComponents.CHANNEL_START);
		}
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity user) {
		return 72000;
	}

	@Override
	public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remainingTicks) {
		if (!(level instanceof ServerLevel server) || !(user instanceof ServerPlayer player)) {
			return;
		}
		channelTick(server, player, getUseDuration(stack, user) - remainingTicks);
	}

	private static void channelTick(ServerLevel server, ServerPlayer player, int ticks) {
		float strength = strength(ticks);
		double radius = radius(strength);
		float maxHardness = maxHardness(strength);

		Vec3 focus = Aim.target(server, player);

		// Gusts stream out from around you towards the whirlwind, more of them the longer you channel.
		// Between them they carry as much force each tick as the whirlwind has, so it does its work
		// only where, and once, the wind arrives.
		float gustRate = Math.min(MAX_GUSTS_PER_TICK, 1 + strength / 20);
		int gusts = (int) gustRate + (player.getRandom().nextFloat() < gustRate % 1 ? 1 : 0);
		double reach = Math.max(MIN_GUST_REACH, radius * GUST_REACH);
		// A gust only covers part of the whirlwind, so it hits harder than the whirlwind does per tick
		double push = (0.08 + 0.04 * Math.sqrt(strength)) * 3 / gustRate;
		int budget = (int) Math.ceil((4 + Math.min(26, strength / 4)) * 2 / gustRate);
		float damage = Math.min(MAX_GUST_DAMAGE, MIN_GUST_DAMAGE + strength / 10);
		for (int i = 0; i < gusts; i++) {
			WindGust.launch(server, player, focus, radius, push * 0.5, landed -> {
				swirlParticles(server, player, landed, reach);
				pushEntities(server, player, landed, reach, focus, push, damage);
				erode(server, player, landed, reach, maxHardness, budget);
			});
		}

		windSounds(server, player, focus, ticks, strength);

		if (ticks % 10 == 0) {
			player.sendOverlayMessage(Component.translatable("item.powermod.staff_of_ancient_winds.channel",
					Component.translatable("item.powermod.staff_of_ancient_winds.tier." + tier(maxHardness)),
					(int) strength, (int) radius).withStyle(ChatFormatting.AQUA));
		}
	}

	@Override
	public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remainingTicks) {
		if (!(level instanceof ServerLevel server) || !(user instanceof ServerPlayer player)) {
			return false;
		}
		int ticks = getUseDuration(stack, user) - remainingTicks;
		if (ticks < MIN_RELEASE_TICKS) {
			return false;
		}
		releaseGust(server, player, ticks);
		player.getCooldowns().addCooldown(stack, RELEASE_COOLDOWN);
		return true;
	}

	private static void releaseGust(ServerLevel server, ServerPlayer player, int ticks) {
		float strength = strength(ticks);
		double radius = radius(strength) * 1.5;

		// One last blast flies out to the whirlwind and hurls everything around it away
		Vec3 target = Aim.target(server, player);
		double force = 1.0 + 0.2 * Math.sqrt(strength);
		server.playSound(null, player.blockPosition(), SoundEvents.WIND_CHARGE_THROW, SoundSource.PLAYERS, 3f, 0.5f);
		server.playSound(null, player.blockPosition(), SoundEvents.BREEZE_SHOOT, SoundSource.PLAYERS, 3f, 0.5f);
		float damage = Math.min(MAX_BLAST_DAMAGE, MIN_BLAST_DAMAGE + strength / 4);
		WindGust.blast(server, player, target, force, pos -> finalBlast(server, player, pos, radius, force, damage));
	}

	private static void finalBlast(ServerLevel server, ServerPlayer player, Vec3 pos, double radius, double force, float damage) {
		pushEntities(server, player, pos, radius, pos, force, damage);
		server.sendParticles(ParticleTypes.GUST_EMITTER_LARGE, pos.x, pos.y + 1, pos.z, 1, 0, 0, 0, 0);
		for (int i = 0; i < 24; i++) {
			double angle = Math.PI * 2 * i / 24;
			server.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y + 1, pos.z, 0, Math.cos(angle), 0.1, Math.sin(angle), 1.2);
		}
		// Heard at both the blast and the wielder, layered for a deafening boom
		for (Vec3 at : new Vec3[] {pos, player.position()}) {
			server.playSound(null, at.x, at.y, at.z, SoundEvents.WIND_CHARGE_BURST, SoundSource.PLAYERS, 4f, 0.5f);
			server.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.PLAYERS, 4f, 0.7f);
			server.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 4f, 0.5f);
			server.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 4f, 0.5f);
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.translatable("item.powermod.staff_of_ancient_winds.tooltip.channel").withStyle(ChatFormatting.AQUA));
		builder.accept(Component.translatable("item.powermod.staff_of_ancient_winds.tooltip.offhand").withStyle(ChatFormatting.AQUA));
		builder.accept(Component.translatable("item.powermod.staff_of_ancient_winds.tooltip.release").withStyle(ChatFormatting.AQUA));
	}

	/**
	 * Layered, ever-denser roar. Each layer plays at the wielder as well as at the whirlwind,
	 * so it stays overwhelming however far away you aim.
	 */
	private static void windSounds(ServerLevel level, ServerPlayer player, Vec3 focus, int ticks, float strength) {
		RandomSource random = player.getRandom();
		Vec3 self = player.position();
		Vec3[] spots = {focus, self};

		if (ticks % 4 == 0) {
			for (Vec3 at : spots) {
				level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_IDLE_AIR, SoundSource.PLAYERS, 4f, 0.5f + random.nextFloat() * 0.2f);
			}
		}
		if (ticks % 15 == 0) {
			for (Vec3 at : spots) {
				level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_WHIRL, SoundSource.PLAYERS, 4f, 0.5f);
			}
		}
		// Rushing-air roar right in your ears
		if (ticks % 40 == 0) {
			level.playSound(null, self.x, self.y, self.z, SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 2f, 0.6f);
		}
		// Random gust bursts, more and more often as the wind builds
		if (random.nextFloat() < Math.min(0.6f, 0.05f + strength / 150f)) {
			Vec3 at = random.nextBoolean() ? focus : self;
			level.playSound(null, at.x, at.y, at.z, SoundEvents.WIND_CHARGE_BURST, SoundSource.PLAYERS, 4f, 0.5f + random.nextFloat() * 0.4f);
		}
		// From Gale onwards, deep buffeting wingbeats of wind
		if (strength >= 16 && ticks % 12 == 0) {
			for (Vec3 at : spots) {
				level.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 4f, 0.5f);
			}
		}
		// From Tempest onwards, a low rumble underneath everything
		if (strength >= 33 && ticks % 20 == 0) {
			for (Vec3 at : spots) {
				level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 2f, 0.5f);
			}
		}
	}

	private static void swirlParticles(ServerLevel level, ServerPlayer player, Vec3 centre, double radius) {
		RandomSource random = player.getRandom();
		int count = 6 + (int) radius;
		for (int i = 0; i < count; i++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double distance = 0.5 + random.nextDouble() * (radius - 0.5);
			double x = centre.x + Math.cos(angle) * distance;
			double z = centre.z + Math.sin(angle) * distance;
			double y = centre.y - 1 + random.nextDouble() * radius * 0.6;
			// Velocity runs along the circle so the particles spiral around the focus
			level.sendParticles(random.nextBoolean() ? ParticleTypes.CLOUD : ParticleTypes.WHITE_ASH,
					x, y, z, 0, -Math.sin(angle), 0.15, Math.cos(angle), 0.5);
		}
		if (random.nextInt(4) == 0) {
			double angle = random.nextDouble() * Math.PI * 2;
			double distance = random.nextDouble() * radius;
			level.sendParticles(ParticleTypes.GUST, centre.x + Math.cos(angle) * distance, centre.y + random.nextDouble() * 3,
					centre.z + Math.sin(angle) * distance, 1, 0, 0, 0, 0);
		}
	}

	/**
	 * Pushes everything within {@code radius} of {@code around} outwards from, and spinning around,
	 * the whirlwind's {@code centre}, and hurts the creatures among them by up to {@code damage}.
	 */
	private static void pushEntities(ServerLevel level, ServerPlayer player, Vec3 around, double radius, Vec3 centre, double force,
			float damage) {
		for (Entity entity : level.getEntities(player, new AABB(around, around).inflate(radius))) {
			if (entity.isSpectator() || entity instanceof OwnableEntity pet && pet.getOwner() == player) {
				continue;
			}
			double distance = entity.position().distanceTo(around);
			Vec3 away = entity.position().subtract(centre);
			if (distance > radius || away.horizontalDistanceSqr() < 0.0001) {
				continue;
			}
			Vec3 outward = new Vec3(away.x, 0, away.z).normalize();
			Vec3 swirl = new Vec3(-outward.z, 0, outward.x);
			double falloff = 1 - 0.5 * distance / radius;
			// Debris only gets swirled around, not lifted, so it comes back down instead of piling up in the air
			double lift = entity instanceof FallingBlockEntity ? 0 : 0.3;
			entity.addDeltaMovement(outward.add(swirl.scale(0.6)).add(0, lift, 0).scale(force * falloff));
			entity.needsSync = true;
			if (entity instanceof LivingEntity victim) {
				victim.hurtServer(level, level.damageSources().playerAttack(player), damage * (float) falloff);
			}
		}
	}

	/** Tears away exposed blocks at random within the radius, up to {@code budget} of them. */
	private static void erode(ServerLevel level, ServerPlayer player, Vec3 centre, double radius, float maxHardness, int budget) {
		RandomSource random = player.getRandom();
		int broken = 0;
		int debris = 0;
		SoundType breakSound = null;
		int samples = (int) Math.min(MAX_SAMPLES_PER_GUST, SAMPLES_PER_BLOCK * 4.2 * radius * radius * radius);
		for (int i = 0; i < samples && broken < budget; i++) {
			// Uniformly random point in the sphere
			Vec3 direction = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize();
			double distance = radius * Math.cbrt(random.nextDouble());
			BlockPos pos = BlockPos.containing(centre.add(direction.scale(distance)));
			if (pos.distToCenterSqr(player.position()) < CALM_RADIUS * CALM_RADIUS || !level.isLoaded(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || state.liquid()) {
				continue;
			}
			float hardness = state.getDestroySpeed(level, pos);
			if (hardness < 0 || hardness > maxHardness || !isExposed(level, pos) || !level.mayInteract(player, pos)) {
				continue;
			}
			// Some blocks are torn loose and thrown into the whirlwind, which then carries them
			// around (pushEntities moves them like any other entity). The rest are removed silently:
			// one break sound per block would flood the sound engine. Storage blocks still spill
			// their contents.
			boolean flung = false;
			if (debris < MAX_DEBRIS_PER_TICK && random.nextFloat() < DEBRIS_CHANCE) {
				Vec3 away = Vec3.atCenterOf(pos).subtract(centre);
				Vec3 outward = new Vec3(away.x, 0, away.z).normalize();
				Vec3 swirl = new Vec3(-outward.z, 0, outward.x);
				flung = Debris.fling(level, pos, outward.scale(0.3).add(swirl.scale(0.5)).add(0, 0.5 + random.nextDouble() * 0.5, 0));
			}
			if (flung) {
				debris++;
			} else {
				level.setBlock(pos, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL, 512);
			}
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
					4, 0.3, 0.3, 0.3, 0.1);
			if (breakSound == null) {
				breakSound = state.getSoundType();
			}
			broken++;
		}
		// A single crumble every other tick stands in for all the blocks torn away
		if (breakSound != null && level.getGameTime() % 2 == 0) {
			level.playSound(null, centre.x, centre.y, centre.z, breakSound.getBreakSound(), SoundSource.BLOCKS, 3f, 0.7f);
		}
	}

	// Only blocks touching air get torn away, so the wind strips terrain layer by layer
	private static boolean isExposed(ServerLevel level, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			if (level.getBlockState(pos.relative(direction)).isAir()) {
				return true;
			}
		}
		return false;
	}
}
