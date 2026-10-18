package com.theca.powermod.power;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * A streak of wind that carries the staff's power. It takes time to get where it's going:
 * it flies along its path, shoving aside anything it passes through, and only does its work
 * when it lands, either at the end of its path or on the first block in its way.
 *
 * Channeling gusts rise up beside the wielder and sweep in a curve towards the whirlwind,
 * so they frame the view instead of blocking it. The final blast flies straight from the staff.
 */
public final class WindGust {
	private static final List<WindGust> ACTIVE = new ArrayList<>();
	// Blocks per tick
	private static final double GUST_SPEED = 3;
	private static final double BLAST_SPEED = 4;
	// Trail particles this close to the wielder's eyes are skipped
	private static final double CLEAR_SIGHT = 2.5;
	// Gusts pass through blocks for this far after setting off, so they aren't stopped by a wall right beside you
	private static final double SQUEEZE_OUT = 4;

	private final ServerLevel level;
	private final ServerPlayer owner;
	private final Vec3 start;
	private final Vec3 control;
	private final Vec3 end;
	private final double length;
	private final double step;
	private final boolean blast;
	private final double shove;
	private final Consumer<Vec3> onImpact;
	private final Set<Entity> shoved = new HashSet<>();
	private double progress;

	private WindGust(ServerLevel level, ServerPlayer owner, Vec3 start, Vec3 control, Vec3 end, boolean blast, double shove,
			Consumer<Vec3> onImpact) {
		this.level = level;
		this.owner = owner;
		this.start = start;
		this.control = control;
		this.end = end;
		this.blast = blast;
		this.shove = shove;
		this.onImpact = onImpact;
		this.length = Math.max(1, start.distanceTo(control) + control.distanceTo(end));
		this.step = (blast ? BLAST_SPEED : GUST_SPEED) / length;
	}

	public static void register() {
		ServerTickEvents.END_LEVEL_TICK.register(level -> ACTIVE.removeIf(gust -> gust.level == level && gust.tick()));
	}

	/**
	 * Sends a gust from somewhere beside the player towards a random spot in the whirlwind
	 * around {@code focus}.
	 *
	 * @param shove    how hard it pushes whatever it flies through
	 * @param onImpact what it does where it lands
	 */
	public static void launch(ServerLevel level, ServerPlayer player, Vec3 focus, double radius, double shove, Consumer<Vec3> onImpact) {
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
		ACTIVE.add(new WindGust(level, player, start, control, end, false, shove, onImpact));
	}

	/** Sends one great blast straight from the staff to {@code target}. */
	public static void blast(ServerLevel level, ServerPlayer player, Vec3 target, double shove, Consumer<Vec3> onImpact) {
		Vec3 start = player.getEyePosition().add(player.getLookAngle().scale(1.5)).add(0, -0.3, 0);
		level.sendParticles(ParticleTypes.GUST_EMITTER_SMALL, start.x, start.y, start.z, 1, 0, 0, 0, 0);
		ACTIVE.add(new WindGust(level, player, start, start.lerp(target, 0.5), target, true, shove, onImpact));
	}

	private Vec3 at(double t) {
		double u = 1 - t;
		return start.scale(u * u).add(control.scale(2 * u * t)).add(end.scale(t * t));
	}

	/** Moves the gust along its path. Returns true once it has landed or blown itself out. */
	private boolean tick() {
		if (owner.isRemoved()) {
			return true;
		}
		double from = progress;
		progress = Math.min(1, progress + step);
		Vec3 previous = at(from);
		Vec3 current = at(progress);
		if (!level.isLoaded(BlockPos.containing(current))) {
			return true;
		}

		// Stopped by the first block in its way once clear of the wielder
		Vec3 landed = progress >= 1 ? end : null;
		if (progress * length > SQUEEZE_OUT) {
			BlockHitResult hit = level.clip(new ClipContext(previous, current, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
					CollisionContext.empty()));
			if (hit.getType() == HitResult.Type.BLOCK) {
				// Just outside the face it hit, so it works on the surface rather than inside the block
				current = hit.getLocation().add(previous.subtract(current).normalize().scale(0.5));
				landed = current;
			}
		}

		shoveThrough(previous, current);
		trail(previous, current);
		if (landed != null) {
			level.sendParticles(blast ? ParticleTypes.GUST_EMITTER_LARGE : ParticleTypes.GUST, true, false,
					landed.x, landed.y, landed.z, 1, 0, 0, 0, 0);
			onImpact.accept(landed);
			return true;
		}
		return false;
	}

	// Wind passes straight through creatures, carrying them along with it
	private void shoveThrough(Vec3 from, Vec3 to) {
		double width = blast ? 1.5 : 0.8;
		Vec3 direction = to.subtract(from).normalize();
		for (Entity entity : level.getEntities(owner, new AABB(from, to).inflate(width))) {
			if (entity.isSpectator() || shoved.contains(entity) || entity instanceof OwnableEntity pet && pet.getOwner() == owner
					|| entity.getBoundingBox().inflate(width).clip(from, to).isEmpty()) {
				continue;
			}
			shoved.add(entity);
			entity.addDeltaMovement(direction.scale(shove).add(0, shove * 0.3, 0));
			entity.needsSync = true;
		}
	}

	private void trail(Vec3 from, Vec3 to) {
		Vec3 motion = to.subtract(from).scale(0.04);
		Vec3 eyes = owner.getEyePosition();
		int points = blast ? 6 : 3;
		for (int i = 1; i <= points; i++) {
			Vec3 point = from.lerp(to, i / (double) points);
			if (point.distanceToSqr(eyes) < CLEAR_SIGHT * CLEAR_SIGHT) {
				continue;
			}
			// Overriding the limiter keeps far-off stretches of the gust visible
			if (blast) {
				level.sendParticles(ParticleTypes.CLOUD, true, false, point.x, point.y, point.z, 4, 0.4, 0.4, 0.4, 0.05);
				if (i == points) {
					level.sendParticles(ParticleTypes.GUST, true, false, point.x, point.y, point.z, 1, 0, 0, 0, 0);
				}
			} else {
				level.sendParticles(i == points ? ParticleTypes.CLOUD : ParticleTypes.WHITE_ASH, true, false,
						point.x, point.y, point.z, 0, motion.x, motion.y, motion.z, 1);
			}
		}
	}
}
