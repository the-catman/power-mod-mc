package com.theca.powermod.power;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class Aim {
	private Aim() {
	}

	/**
	 * The spot the player is looking at, as far as the server keeps chunks loaded
	 * (one chunk short of the edge, to be safe). Looking at the sky drops the spot
	 * onto the ground at the end of the ray.
	 */
	public static Vec3 target(ServerLevel level, Player player) {
		return target(level, player, false);
	}

	/** @param stopAtFluids whether a water or lava surface counts as what the player is looking at */
	public static Vec3 target(ServerLevel level, Player player, boolean stopAtFluids) {
		double range = Math.max(1, level.getServer().getPlayerList().getViewDistance() - 1) * 16;
		HitResult hit = player.pick(range, 1f, stopAtFluids);
		Vec3 pos = hit.getLocation();
		if (hit.getType() == HitResult.Type.BLOCK) {
			return pos;
		}
		// Pull the spot back towards the player if the end of the ray isn't loaded
		Vec3 eye = player.getEyePosition();
		for (double t = 1; t > 0; t -= 0.1) {
			Vec3 point = eye.lerp(pos, t);
			BlockPos column = BlockPos.containing(point.x, player.getY(), point.z);
			if (level.isLoaded(column)) {
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ());
				return new Vec3(point.x, y, point.z);
			}
		}
		return player.position();
	}
}
