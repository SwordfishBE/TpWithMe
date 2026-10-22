package net.tpwithme.handler;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.config.TpWithMeConfig;
import net.tpwithme.util.SafetyChecker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/** Waits for terrain requested by the player's teleport; never loads chunks itself. */
public final class PendingMountTeleports {
    private static final int TIMEOUT_TICKS = 100;
    private record Pending(UUID vehicleId, ResourceKey<Level> sourceDimension,
                           ResourceKey<Level> targetDimension, Vec3 sourcePos, Vec3 targetPos,
                           Set<UUID> passengerIds, int deadline) {}
    private static final Map<UUID, Pending> pending = new HashMap<>();

    private PendingMountTeleports() {}

    public static void schedule(ServerPlayer player, Entity vehicle, Vec3 targetPos) {
        pending.put(player.getUUID(), new Pending(vehicle.getUUID(), vehicle.level().dimension(),
                player.level().dimension(), vehicle.position(), targetPos,
                TeleportHandler.getOtherPassengerIds(vehicle, player.getUUID()),
                player.level().getServer().getTickCount() + TIMEOUT_TICKS));
    }

    public static void cancel(UUID playerId) {
        pending.remove(playerId);
    }

    public static void clear() {
        pending.clear();
    }

    public static void tick(MinecraftServer server) {
        for (UUID playerId : List.copyOf(pending.keySet())) {
            Pending request = pending.get(playerId);
            if (request == null) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            var config = TpWithMeConfig.get();
            if (player == null || !player.isAlive() || player.isRemoved() || player.isShiftKeyDown()
                    || player.getVehicle() != null || !config.enabled
                    || player.level().dimension() != request.targetDimension()
                    || player.position().distanceToSqr(request.targetPos()) > 4.0) {
                pending.remove(playerId);
                continue;
            }
            var source = server.getLevel(request.sourceDimension());
            Entity vehicle = source == null ? null : source.getEntity(request.vehicleId());
            if (vehicle == null || !vehicle.isAlive() || vehicle.isRemoved()
                    || vehicle.position().distanceToSqr(request.sourcePos()) > 1.0) {
                pending.remove(playerId);
                player.sendSystemMessage(Component.literal(
                        "[TpWithMe] Your mount moved or is no longer loaded where you left it - transfer canceled."));
                continue;
            }
            if (!TeleportHandler.getOtherPassengerIds(vehicle, playerId).equals(request.passengerIds())) {
                pending.remove(playerId);
                player.sendSystemMessage(Component.literal(
                        "[TpWithMe] Passengers changed while waiting - your mount stayed behind."));
                continue;
            }
            if (server.getTickCount() >= request.deadline()) {
                pending.remove(playerId);
                player.sendSystemMessage(Component.literal(
                        "[TpWithMe] Destination terrain did not finish loading - your mount stayed behind."));
                continue;
            }
            if (config.checkSafety) {
                boolean chunksReady = SafetyChecker.areSearchChunksLoaded(vehicle, player.level(),
                        request.targetPos(), config.safetySearchRadius);
                // Retry the bounded neighborhood search every five ticks while terrain is incomplete.
                if (!chunksReady && (server.getTickCount() - (request.deadline() - TIMEOUT_TICKS)) % 5 != 0) continue;
                if (SafetyChecker.findSafePosition(vehicle, player.level(), request.targetPos(), config.safetySearchRadius) == null
                        && !chunksReady) continue;
            }
            pending.remove(playerId);
            if (TeleportHandler.canResumeTeleport(player, vehicle, player.level())) {
                TeleportHandler.onPostTeleport(player, vehicle, player.level(), request.targetPos());
            }
        }
    }
}
