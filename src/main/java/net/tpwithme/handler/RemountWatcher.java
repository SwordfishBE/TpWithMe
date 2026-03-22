package net.tpwithme.handler;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.TpWithMe;

import java.util.*;

/**
 * Watches for post-teleport dismounts.
 *
 * Problem: SafetyChecker passes, vehicle is teleported, startRiding() succeeds,
 * but Minecraft detects a block collision on the next tick and forces a dismount.
 * The player and vehicle are now separated at the same location.
 *
 * Solution: 2 ticks after teleport, check if the player is still mounted on
 * the expected vehicle. If not (dismounted), teleport the vehicle to the
 * player's current position and attempt remount again.
 */
public final class RemountWatcher {

    private RemountWatcher() {}

    private record PendingCheck(ServerPlayer player, UUID vehicleId, int ticksLeft) {}

    private static final List<PendingCheck> pending =
            Collections.synchronizedList(new ArrayList<>());

    /** Call this from TeleportHandler after a successful startRiding(). */
    public static void schedule(ServerPlayer player, UUID vehicleId) {
        pending.add(new PendingCheck(player, vehicleId, 3));
    }

    /** Register the tick listener once on mod init. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(RemountWatcher::onTick);
    }

    private static void onTick(MinecraftServer server) {
        if (pending.isEmpty()) return;

        List<PendingCheck> toProcess = new ArrayList<>(pending);
        pending.clear();

        for (PendingCheck check : toProcess) {
            int newTicks = check.ticksLeft() - 1;

            if (newTicks > 0) {
                // Not yet – re-queue with decremented counter
                pending.add(new PendingCheck(check.player(), check.vehicleId(), newTicks));
                continue;
            }

            // Time to check
            ServerPlayer player = check.player();
            if (!player.isAlive()) continue;

            Entity currentVehicle = player.getVehicle();

            if (currentVehicle != null && currentVehicle.getUUID().equals(check.vehicleId())) {
                // Still mounted – all good
                continue;
            }

            // Player was dismounted. Find the vehicle and move it to the player.
            ServerLevel level = (ServerLevel) player.level();
            Entity vehicle = level.getEntity(check.vehicleId());

            if (vehicle == null) {
                TpWithMe.LOGGER.warn("[TpWithMe] RemountWatcher: vehicle {} not found in {}.",
                        check.vehicleId(), level.dimension());
                continue;
            }

            TpWithMe.LOGGER.info("[TpWithMe] RemountWatcher: player {} was dismounted – " +
                    "moving vehicle to player and remounting.", player.getName().getString());

            // Teleport vehicle to player's current position
            Vec3 playerPos = player.position();
            TeleportTransition transition = new TeleportTransition(
                    level,
                    playerPos,
                    Vec3.ZERO,
                    player.getYRot(),
                    0.0f,
                    Set.of(),
                    TeleportTransition.DO_NOTHING
            );
            Entity newVehicle = vehicle.teleport(transition);

            if (newVehicle != null) {
                boolean success = player.startRiding(newVehicle, true, false);
                if (success) {
                    TpWithMe.LOGGER.info("[TpWithMe] RemountWatcher: remount successful.");
                } else {
                    TpWithMe.LOGGER.warn("[TpWithMe] RemountWatcher: remount failed – " +
                            "vehicle is at player position but player is not riding.");
                }
            }
        }
    }
}
