package com.theca.powermod.power;

import com.theca.powermod.PowerMod;
import com.theca.powermod.item.HourglassOfEternityItem;
import com.theca.powermod.item.HourglassOfStillnessItem;
import com.theca.powermod.item.ModItems;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * A sphere where time stands still. Everything inside except players stops dead: mobs
 * mid-stride, arrows mid-flight, falling sand and lit TNT. Things that drift in freeze too.
 * When the bubble ends, everything picks up exactly where it left off, all at once.
 *
 * Freezing works by skipping entity ticks (see ServerLevelMixin and the client's
 * ClientLevelMixin). The server tells clients where the bubbles are so they stop
 * animating and predicting frozen entities too.
 */
public final class TimeBubble {
	public static final int DURATION = 100;
	public static final float RADIUS = 10;
	// How far an eternal bubble's owner must move before clients are told where it went
	private static final double RESYNC_DISTANCE = 0.1;
	private static final DustParticleOptions SHELL = new DustParticleOptions(0xB98CFF, 1.2f);

	private static final List<TimeBubble> ACTIVE = new ArrayList<>();
	// Bubbles in the level the client is in, as last sent by the server
	private static List<Zone> clientZones = List.of();

	private final ServerLevel level;
	private final UUID owner;
	// Eternal bubbles follow their owner and last until switched off
	private final boolean eternal;
	private Zone zone;
	private int ticksLeft = DURATION;
	private boolean stopping;
	private boolean moved;

	private TimeBubble(ServerLevel level, UUID owner, Zone zone, boolean eternal) {
		this.level = level;
		this.owner = owner;
		this.zone = zone;
		this.eternal = eternal;
	}

	public record Zone(Vec3 centre, float radius) {
		static final StreamCodec<ByteBuf, Zone> STREAM_CODEC = StreamCodec.composite(
				Vec3.STREAM_CODEC, Zone::centre, ByteBufCodecs.FLOAT, Zone::radius, Zone::new);

		boolean contains(Vec3 pos) {
			return pos.distanceToSqr(centre) <= radius * radius;
		}
	}

	/** Every bubble in the receiving player's level; sent whenever one starts or ends. */
	public record SyncPayload(List<Zone> zones) implements CustomPacketPayload {
		public static final Type<SyncPayload> TYPE = new Type<>(PowerMod.id("time_bubbles"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC =
				Zone.STREAM_CODEC.apply(ByteBufCodecs.list()).map(SyncPayload::new, SyncPayload::zones).cast();

		@Override
		public Type<SyncPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			boolean changed = false;
			for (TimeBubble bubble : List.copyOf(ACTIVE)) {
				if (bubble.level != level) {
					continue;
				}
				if (bubble.tick()) {
					ACTIVE.remove(bubble);
					changed = true;
				}
				changed |= bubble.moved;
			}
			if (changed) {
				sync(level);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendTo(handler.player, handler.player.level()));
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> sendTo(player, to));
	}

	/** Called by the client when the server sends the bubbles in its level. */
	public static void setClientZones(List<Zone> zones) {
		clientZones = List.copyOf(zones);
	}

	public static boolean isActive(UUID owner) {
		return ACTIVE.stream().anyMatch(bubble -> bubble.owner.equals(owner));
	}

	/**
	 * Starts a bubble around the owner.
	 *
	 * @param eternal whether it follows the owner and lasts until stopped, instead of
	 *                staying put and running out after {@link #DURATION} ticks
	 */
	public static void start(ServerLevel level, ServerPlayer owner, boolean eternal) {
		Vec3 centre = centreOn(owner);
		ACTIVE.add(new TimeBubble(level, owner.getUUID(), new Zone(centre, RADIUS), eternal));
		sync(level);
		level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 2f, 0.5f);
		level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 2f, 0.5f);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, centre.x, centre.y, centre.z, 150, 1, 1, 1, 1.5);
	}

	/** Ends the owner's bubble early, as if it had run out. */
	public static void stop(UUID owner) {
		for (TimeBubble bubble : ACTIVE) {
			if (bubble.owner.equals(owner)) {
				bubble.stopping = true;
			}
		}
	}

	private static Vec3 centreOn(Player player) {
		return player.position().add(0, 1, 0);
	}

	/** Whether this entity is held still by a bubble. Players, and anything carrying one, are never frozen. */
	public static boolean isFrozen(Entity entity) {
		if (entity instanceof Player) {
			return false;
		}
		Vec3 pos = entity.position();
		boolean inside = false;
		if (entity.level().isClientSide()) {
			for (Zone zone : clientZones) {
				inside |= zone.contains(pos);
			}
		} else {
			for (TimeBubble bubble : ACTIVE) {
				inside |= bubble.level == entity.level() && bubble.zone.contains(pos);
			}
		}
		if (!inside) {
			return false;
		}
		for (Entity passenger : entity.getIndirectPassengers()) {
			if (passenger instanceof Player) {
				return false;
			}
		}
		return true;
	}

	/** Returns true once the bubble has ended. */
	private boolean tick() {
		moved = false;
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
		if (eternal) {
			// Lasts as long as its owner is here, alive, and still carrying the hourglass
			if (player == null || !player.isAlive() || player.level() != level
					|| !player.getInventory().contains(stack -> stack.getItem() instanceof HourglassOfEternityItem)) {
				stopping = true;
			} else if (centreOn(player).distanceToSqr(zone.centre()) > RESYNC_DISTANCE * RESYNC_DISTANCE) {
				zone = new Zone(centreOn(player), zone.radius());
				moved = true;
			}
		} else if (ticksLeft-- <= 0) {
			stopping = true;
		}
		Vec3 centre = zone.centre();
		if (stopping) {
			level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 2f, 0.5f);
			level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.5f, 0.6f);
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, centre.x, centre.y, centre.z, 150, zone.radius() / 2, zone.radius() / 2,
					zone.radius() / 2, 0.2);
			// The hourglass recharges once the bubble is over, wherever it is in the owner's inventory
			if (!eternal && player != null) {
				player.getCooldowns().addCooldown(ModItems.HOURGLASS_OF_STILLNESS.getDefaultInstance(), HourglassOfStillnessItem.COOLDOWN);
			}
			return true;
		}
		// Faint shimmer on the bubble's surface
		RandomSource random = level.getRandom();
		for (int i = 0; i < 30; i++) {
			Vec3 point = centre.add(new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize().scale(zone.radius()));
			level.sendParticles(i % 3 == 0 ? ParticleTypes.REVERSE_PORTAL : SHELL, point.x, point.y, point.z, 1, 0, 0, 0, 0);
		}
		// A clock's tick each second, slowing as the bubble winds down. Eternal bubbles never wind down, so theirs holds steady.
		if (eternal) {
			if (level.getGameTime() % 20 == 0) {
				level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 2f, 0.75f);
			}
		} else if (ticksLeft % 20 == 0) {
			float pitch = 0.5f + 0.5f * ticksLeft / DURATION;
			level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 2f, pitch);
		}
		return false;
	}

	private static void sync(ServerLevel level) {
		for (ServerPlayer player : PlayerLookup.level(level)) {
			sendTo(player, level);
		}
	}

	private static void sendTo(ServerPlayer player, ServerLevel level) {
		List<Zone> zones = ACTIVE.stream().filter(bubble -> bubble.level == level).map(bubble -> bubble.zone).toList();
		ServerPlayNetworking.send(player, new SyncPayload(zones));
	}
}
