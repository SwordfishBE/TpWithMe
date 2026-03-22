package net.tpwithme.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.TpWithMe;

/**
 * Checks whether a destination position is safe for a mount and its rider
 * by iterating over all BlockPos values within the combined bounding box
 * and checking if any block is solid (has a non-empty collision shape).
 *
 * Why not noCollision():
 *   noCollision(null, box) is unreliable in 1.21.11 — it skips entity-aware
 *   collision logic and may return true even when solid blocks are present.
 *   Direct BlockPos iteration against BlockState#isSolid() is dependable.
 *
 * Box checked:
 *   - Vehicle standing box (always Pose.STANDING regardless of current pose)
 *   - + RIDER_CLEARANCE blocks above for the player
 */
public final class SafetyChecker {

    private SafetyChecker() {}

    private static final double RIDER_CLEARANCE = 2.0;

    public static boolean isSafe(Entity vehicle, ServerLevel level, Vec3 targetPos) {
        EntityDimensions dims = vehicle.getDimensions(Pose.STANDING);
        double halfW  = dims.width()  / 2.0;
        double totalH = dims.height() + RIDER_CLEARANCE;

        // Block positions that the combined vehicle+rider box overlaps
        int minX = (int) Math.floor(targetPos.x - halfW);
        int minY = (int) Math.floor(targetPos.y);
        int minZ = (int) Math.floor(targetPos.z - halfW);
        int maxX = (int) Math.floor(targetPos.x + halfW);
        int maxY = (int) Math.floor(targetPos.y + totalH);
        int maxZ = (int) Math.floor(targetPos.z + halfW);

        TpWithMe.LOGGER.debug("[TpWithMe] SafetyCheck: {} box [{},{},{}] to [{},{},{}]",
                vehicle.getType().toShortString(), minX, minY, minZ, maxX, maxY, maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.getCollisionShape(level, pos).isEmpty()) {
                        TpWithMe.LOGGER.debug("[TpWithMe] SafetyCheck: blocked by {} at [{},{},{}]",
                                state.getBlock(), x, y, z);
                        return false;
                    }
                }
            }
        }

        return true;
    }
}
