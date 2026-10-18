package com.theca.powermod.power;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Blocks torn out of the ground and thrown through the air; they land as blocks again, like sand. */
public final class Debris {
	private Debris() {
	}

	/**
	 * Rips the block out and launches it. Only plain full blocks fly: anything with contents,
	 * and partial blocks like plants or slabs, are left for the caller to break normally.
	 * Callers check that the block may be broken at all. Returns whether it was launched.
	 */
	public static boolean fling(ServerLevel level, BlockPos pos, Vec3 velocity) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || state.hasBlockEntity() || !state.isCollisionShapeFullBlock(level, pos)) {
			return false;
		}
		FallingBlockEntity debris = FallingBlockEntity.fall(level, pos, state);
		debris.setDeltaMovement(velocity);
		debris.needsSync = true;
		return true;
	}
}
