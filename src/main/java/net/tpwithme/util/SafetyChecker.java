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
 * Checks whether a destination position is safe for a mount and its rider,
 * and optionally finds a nearby safe position if the exact destination is blocked.
 *
 * Safety box:
 *   - Vehicle standing box (always Pose.STANDING regardless of current pose)
 *   - + RIDER_CLEARANCE blocks above for the player
 *
 * If the exact destination is blocked, findSafePosition() searches nearby
 * offsets (up to SEARCH_RADIUS blocks) in priority order:
 *   1. Y upward first (most natural — surface above an obstacle)
 *   2. Then X/Z horizontal offsets
 *   3. Then Y downward
 */
public final class SafetyChecker {

    private SafetyChecker() {}

    private static final double RIDER_CLEARANCE = 2.0;

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns true if the exact position is safe.
     */
    public static boolean isSafe(Entity vehicle, ServerLevel level, Vec3 pos) {
        return checkPos(vehicle, level, pos) == null;
    }

    /**
     * Returns the best safe position at or near {@code targetPos}.
     *
     * Search order (priority):
     *   1. Exact position
     *   2. Y+1, Y+2  (upward)
     *   3. All X/Z offsets within radius at same Y
     *   4. Y-1, Y-2  (downward, last resort)
     *
     * @param vehicle      The mount entity.
     * @param level        Destination level.
     * @param targetPos    Requested destination.
     * @param searchRadius Max blocks to search in each direction (0 = exact only).
     * @return A safe Vec3 position, or {@code null} if none found within radius.
     */
    public static Vec3 findSafePosition(Entity vehicle, ServerLevel level,
                                        Vec3 targetPos, int searchRadius) {
        // 1. Try exact position first
        if (isSafe(vehicle, level, targetPos)) {
            return targetPos;
        }

        if (searchRadius <= 0) return null;

        // 2. Try Y upward (most natural adjustment)
        for (int dy = 1; dy <= searchRadius; dy++) {
            Vec3 candidate = targetPos.add(0, dy, 0);
            if (isSafe(vehicle, level, candidate)) {
                TpWithMe.LOGGER.debug("{} SafetyCheck: adjusted position +Y{} to {}",
                        TpWithMe.prefix(),
                        dy, candidate);
                return candidate;
            }
        }

        // 3. Try horizontal X/Z offsets at same Y and Y±1
        for (int dy = 0; dy <= searchRadius; dy++) {
            for (int sign = 1; sign >= -1; sign -= 2) { // +dy first, then -dy
                int yOffset = dy * sign;
                if (dy == 0 && sign == -1) continue; // skip dy=0 twice

                for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                    for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                        if (dx == 0 && dz == 0 && yOffset == 0) continue; // already tried
                        Vec3 candidate = targetPos.add(dx, yOffset, dz);
                        if (isSafe(vehicle, level, candidate)) {
                            TpWithMe.LOGGER.debug(
                                    "{} SafetyCheck: adjusted position [{},{},{}] to {}",
                                    TpWithMe.prefix(), dx, yOffset, dz, candidate);
                            return candidate;
                        }
                    }
                }
            }
        }

        return null;
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    /**
     * Returns the first blocking block at {@code pos}, or null if clear.
     */
    private static BlockPos checkPos(Entity vehicle, ServerLevel level, Vec3 pos) {
        EntityDimensions dims = vehicle.getDimensions(Pose.STANDING);
        double halfW  = dims.width()  / 2.0;
        double totalH = dims.height() + RIDER_CLEARANCE;

        int minX = (int) Math.floor(pos.x - halfW);
        int minY = (int) Math.floor(pos.y);
        int minZ = (int) Math.floor(pos.z - halfW);
        int maxX = (int) Math.floor(pos.x + halfW);
        int maxY = (int) Math.floor(pos.y + totalH);
        int maxZ = (int) Math.floor(pos.z + halfW);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(bp);
                    if (!state.getCollisionShape(level, bp).isEmpty()) {
                        TpWithMe.LOGGER.debug(
                                "{} SafetyCheck: blocked by {} at [{},{},{}]",
                                TpWithMe.prefix(), state.getBlock(), x, y, z);
                        return bp;
                    }
                }
            }
        }
        return null;
    }
}
