package net.tpwithme.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.tpwithme.config.TpWithMeConfig;

public final class SafetyChecker {
    private static final double RIDER_CLEARANCE = 2.0;
    private static final int MAX_CANDIDATES = 1024;

    private SafetyChecker() {}

    private static AABB clearanceBox(Entity vehicle, Vec3 pos) {
        return vehicle.getDimensions(Pose.STANDING).makeBoundingBox(pos).expandTowards(0, RIDER_CLEARANCE, 0);
    }

    public static boolean isWithinBounds(Entity vehicle, ServerLevel level, Vec3 pos) {
        if (!Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)) return false;
        AABB box = clearanceBox(vehicle, pos);
        var border = level.getWorldBorder();
        return box.minY >= level.getMinY() && box.maxY <= level.getMaxY()
                && box.minX >= border.getMinX() && box.maxX <= border.getMaxX()
                && box.minZ >= border.getMinZ() && box.maxZ <= border.getMaxZ();
    }

    public static Vec3 findSafePosition(Entity vehicle, ServerLevel level, Vec3 targetPos, int searchRadius) {
        searchRadius = Math.clamp(searchRadius, 0, TpWithMeConfig.MAX_SAFETY_SEARCH_RADIUS);
        if (isSafe(vehicle, level, targetPos)) return targetPos;
        int checked = 1;
        for (int dy = 1; dy <= searchRadius; dy++) {
            Vec3 candidate = targetPos.add(0, dy, 0);
            checked++;
            if (isSafe(vehicle, level, candidate)) return candidate;
        }
        for (int dy = 0; dy <= searchRadius; dy++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                if (dy == 0 && sign == -1) continue;
                for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                    for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                        if (dx == 0 && dz == 0 && sign == 1) continue;
                        if (++checked > MAX_CANDIDATES) return null;
                        Vec3 candidate = targetPos.add(dx, dy * sign, dz);
                        if (isSafe(vehicle, level, candidate)) return candidate;
                    }
                }
            }
        }
        return null;
    }

    public static boolean isSafe(Entity vehicle, ServerLevel level, Vec3 pos) {
        if (!isWithinBounds(vehicle, level, pos)) return false;
        AABB body = clearanceBox(vehicle, pos).deflate(1.0E-7);
        AABB support = new AABB(body.minX, pos.y - 0.125, body.minZ, body.maxX, pos.y, body.maxZ);
        int minX = Mth.floor(body.minX), maxX = Mth.floor(body.maxX);
        int minZ = Mth.floor(body.minZ), maxZ = Mth.floor(body.maxZ);
        // Searching must not synchronously load/generate chunks on the server thread.
        for (int x = minX >> 4; x <= (maxX >> 4); x++) {
            for (int z = minZ >> 4; z <= (maxZ >> 4); z++) {
                if (!level.hasChunk(x, z)) return false;
            }
        }
        boolean supported = vehicle instanceof HappyGhast;
        CollisionContext context = CollisionContext.of(vehicle);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = Mth.floor(support.minY); y <= Mth.floor(body.maxY); y++) {
                    BlockPos block = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(block);
                    AABB blockBox = new AABB(block);
                    boolean inBody = blockBox.intersects(body);
                    boolean underFeet = blockBox.intersects(support);
                    var fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        boolean waterMount = vehicle instanceof AbstractNautilus && fluid.is(FluidTags.WATER);
                        double surface = y + fluid.getHeight(level, block);
                        boolean lavaSurface = vehicle instanceof Strider && fluid.is(FluidTags.LAVA)
                                && pos.y >= surface - 1.0E-6 && pos.y - surface <= 0.125;
                        if (!waterMount && !lavaSurface && (inBody || underFeet)) return false;
                        if (waterMount || lavaSurface) supported = true;
                    }
                    if ((inBody || underFeet) && isHazardous(state)) return false;
                    for (AABB shape : state.getCollisionShape(level, block, context).toAabbs()) {
                        AABB worldShape = shape.move(x, y, z);
                        if (worldShape.intersects(body)) return false;
                        if (worldShape.intersects(support)) supported = true;
                    }
                }
            }
        }
        return supported;
    }

    private static boolean isHazardous(BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.LAVA_CAULDRON);
    }
}
