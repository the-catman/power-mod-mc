package com.theca.powermod.client;

import com.theca.powermod.item.FlightStaffItem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Staff of Flight physics for the local player. Players control their own movement,
 * so this runs on the client and the server just accepts the result.
 *
 * Jump again in mid-air to take off. Hold forward to ride the wind towards where you look:
 * speed builds up gradually and sharp turns bleed it off. Let go to drift and sink slowly;
 * sneak to sink faster. Landing ends the ride.
 */
public final class WindRider {
	// Blocks per tick; about 2.5x sprinting
	private static final double TOP_SPEED = 0.7;
	// How strongly velocity is pulled towards the look direction each tick
	private static final double STEER = 0.05;
	private static final double DRIFT_SINK = -0.1;
	private static final double DIVE_SINK = -0.6;
	private static final double DRIFT_DRAG = 0.97;
	private static final double TAKEOFF_LIFT = 0.7;
	private static final double DASH_SPEED = 1.1;

	private static boolean riding;
	private static Vec3 velocity = Vec3.ZERO;
	private static boolean jumpWasDown;

	private WindRider() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(WindRider::tick);
		FlightStaffItem.clientDash = WindRider::dash;
	}

	private static boolean canRide(Player player) {
		return FlightStaffItem.isHolding(player) && player.isAlive() && !player.isSpectator()
				&& !player.getAbilities().flying && !player.isFallFlying() && !player.isPassenger();
	}

	private static void tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.isPaused()) {
			return;
		}
		boolean jumpDown = minecraft.options.keyJump.isDown();
		boolean jumpPressed = jumpDown && !jumpWasDown;
		jumpWasDown = jumpDown;

		if (!canRide(player)) {
			riding = false;
			return;
		}
		if (!riding) {
			// Water and lava count as mid-air, so a jump there lifts you straight out
			boolean liquid = player.isInWater() || player.isInLava();
			if (!jumpPressed || (player.onGround() && !liquid)) {
				return;
			}
			// Second jump in mid-air: a gust lifts you up
			Vec3 current = player.getDeltaMovement();
			start(player, new Vec3(current.x, TAKEOFF_LIFT, current.z));
		}
		if (player.onGround() && velocity.y <= 0) {
			riding = false;
			return;
		}

		// Walls and ceilings stop you instead of the flight pushing into them forever
		if (player.horizontalCollision) {
			velocity = new Vec3(0, velocity.y, 0);
		}
		if (player.verticalCollision && velocity.y > 0) {
			velocity = new Vec3(velocity.x, 0, velocity.z);
		}

		if (minecraft.options.keyUp.isDown()) {
			Vec3 target = player.getLookAngle().scale(TOP_SPEED);
			velocity = velocity.add(target.subtract(velocity).scale(STEER));
		} else {
			double sink = minecraft.options.keyShift.isDown() ? DIVE_SINK : DRIFT_SINK;
			velocity = new Vec3(velocity.x * DRIFT_DRAG, velocity.y + (sink - velocity.y) * 0.15, velocity.z * DRIFT_DRAG);
		}
		player.setDeltaMovement(velocity);
		effects(player);
	}

	private static void dash(Player player) {
		if (!(player instanceof LocalPlayer) || !canRide(player)) {
			return;
		}
		Vec3 base = riding ? velocity : player.getDeltaMovement();
		Vec3 boosted = base.add(player.getLookAngle().scale(DASH_SPEED));
		if (player.onGround()) {
			boosted = new Vec3(boosted.x, Math.max(boosted.y, 0.5), boosted.z);
		}
		start(player, boosted);
	}

	private static void start(Player player, Vec3 initialVelocity) {
		riding = true;
		velocity = initialVelocity;
		player.setDeltaMovement(velocity);
		for (int i = 0; i < 12; i++) {
			double angle = Math.PI * 2 * i / 12;
			player.level().addParticle(ParticleTypes.CLOUD, player.getX(), player.getY(), player.getZ(),
					Math.cos(angle) * 0.25, -0.05, Math.sin(angle) * 0.25);
		}
	}

	private static void effects(Player player) {
		double speed = velocity.length();
		// A light trail of wind behind you, thicker the faster you go
		if (player.getRandom().nextDouble() < 0.3 + speed) {
			Vec3 behind = player.position().subtract(velocity.normalize().scale(0.6));
			player.level().addParticle(player.isInWater() ? ParticleTypes.BUBBLE : ParticleTypes.CLOUD, behind.x, behind.y + 0.4, behind.z,
					-velocity.x * 0.2, -velocity.y * 0.2, -velocity.z * 0.2);
		}
	}
}
