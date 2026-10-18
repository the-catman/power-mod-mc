package com.theca.powermod.power;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * A streak of wind that rises up beside the wielder and sweeps in a curve towards the
 * whirlwind, leaving a cloudy trail. They start off to the sides rather than at the
 * player's eyes, so they frame the view instead of blocking it.
 */
public final class WindGust {
	private static final List<WindGust> ACTIVE = new ArrayList<>();
	// Blocks per tick
	private static final double SPEED = 2.5;
	// Trail particles this close to the wielder's eyes are skipped
	private static final double CLEAR_SIGHT = 2.5;

	private final ServerLevel level;
	private final ServerPlayer owner;
	private final Vec3 start;
	private final Vec3 control;
	private final Vec3 end;
	private final double step;
	private double progress;

	private WindGust(ServerLevel level, ServerPlayer owner, Vec3 start, Vec3 control, Vec3 end) {
		this.level = level;
		this.owner = owner;
		this.start = start;
		this.control = control;
		this.end = end;
		this.step = SPEED / Math.max(1, start.distanceTo(control) + control.distanceTo(end));
	}

	public static void register() {
		ServerTickEvents.END_LEVEL_TICK.register(level -> ACTIVE.removeIf(gust -> gust.level == level && gust.tick()));
	}

	/** Sends a gust from somewhere beside the player towards a random spot in the whirlwind around {@code focus}. */
	public static void launch(ServerLevel level, ServerPlayer player, Vec3 focus, double radius) {
		RandomSource random = player.getRandom();
		Vec3 forward = Vec3.directionFromRotation(0, player.getYRot());
		Vec3 right = new Vec3(-forward.z, 0, forward.x);

		// Beside, above and a little behind the player: never straight out of their eyes
		double side = (random.nextBoolean() ? 1 : -1) * (2 + random.nextDouble() * 3);
		Vec3 start = player.getEyePosition()
				.add(right.scale(side))
				.add(0, -0.5 + random.nextDouble() * 3, 0)
				.add(forward.scale(-random.nextDouble() * 2));

		double angle = random.nextDouble() * Math.PI * 2;
		double distance = random.nextDouble() * radius * 0.8;
		Vec3 end = focus.add(Math.cos(angle) * distance, random.nextDouble() * radius * 0.4, Math.sin(angle) * distance);

		// Bowing the path outwards, on the side the gust started from, and upwards gives it a sweeping arc
		double length = start.distanceTo(end);
		Vec3 control = start.lerp(end, 0.4)
				.add(right.scale(Math.signum(side) * length * (0.1 + random.nextDouble() * 0.2)))
				.add(0, length * random.nextDouble() * 0.15, 0);

		level.sendParticles(ParticleTypes.SMALL_GUST, start.x, start.y, start.z, 1, 0, 0, 0, 0);
		ACTIVE.add(new WindGust(level, player, start, control, end));
	}

	private Vec3 at(double t) {
		double u = 1 - t;
		return start.scale(u * u).add(control.scale(2 * u * t)).add(end.scale(t * t));
	}

	/** Moves the gust along its path. Returns true once it has reached the whirlwind. */
	private boolean tick() {
		double from = progress;
		progress = Math.min(1, progress + step);
		Vec3 previous = at(from);
		Vec3 current = at(progress);
		Vec3 motion = current.subtract(previous).scale(0.04);
		Vec3 eyes = owner.getEyePosition();
		for (int i = 1; i <= 3; i++) {
			Vec3 point = previous.lerp(current, i / 3.0);
			if (point.distanceToSqr(eyes) < CLEAR_SIGHT * CLEAR_SIGHT) {
				continue;
			}
			// Overriding the limiter keeps far-off stretches of the gust visible
			level.sendParticles(i == 3 ? ParticleTypes.CLOUD : ParticleTypes.WHITE_ASH, true, false,
					point.x, point.y, point.z, 0, motion.x, motion.y, motion.z, 1);
		}
		if (progress >= 1) {
			level.sendParticles(ParticleTypes.GUST, true, false, end.x, end.y, end.z, 1, 0, 0, 0, 0);
			return true;
		}
		return false;
	}
}
