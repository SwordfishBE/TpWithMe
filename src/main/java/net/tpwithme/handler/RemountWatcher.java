package net.tpwithme.handler;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.compat.OpenPartiesAndClaimsCompat.TeleportType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/** ID-only recovery queue; recovery never teleports a mount. */
public final class RemountWatcher {
    private RemountWatcher() {}

    private record PendingCheck(UUID vehicleId, ResourceKey<Level> dimension, Vec3 vehiclePos,
                                Vec3 playerPos, TeleportType type, boolean crossDimensional,
                                Set<UUID> passengerIds, int dueTick) {}

    private static final Map<UUID, PendingCheck> pending = new HashMap<>();

    public static void schedule(ServerPlayer player, Entity vehicle, TeleportType type, boolean crossDimensional) {
        pending.put(player.getUUID(), new PendingCheck(vehicle.getUUID(), player.level().dimension(),
                vehicle.position(), player.position(), type, crossDimensional,
                TeleportHandler.getOtherPassengerIds(vehicle, player.getUUID()), player.level().getServer().getTickCount() + 3));
    }

    public static void cancel(UUID playerId) {
        pending.remove(playerId);
        PendingMountTeleports.cancel(playerId);
    }

    public static void clear() {
        pending.clear();
        PendingMountTeleports.clear();
        TeleportHandler.clearState();
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(RemountWatcher::onTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> cancel(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> clear());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
    }

    private static void onTick(MinecraftServer server) {
        PendingMountTeleports.tick(server);
        // Mount callbacks from other mods may schedule/cancel recovery during this loop.
        for (UUID playerId : List.copyOf(pending.keySet())) {
            PendingCheck check = pending.get(playerId);
            if (check == null) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !player.isAlive() || player.isRemoved() || player.isShiftKeyDown()
                    || player.level().dimension() != check.dimension()
                    || (player.getVehicle() != null && !player.getVehicle().getUUID().equals(check.vehicleId()))) {
                pending.remove(playerId);
                continue;
            }
            if (server.getTickCount() < check.dueTick()) continue;
            pending.remove(playerId);
            if (player.getVehicle() != null) continue;
            Entity vehicle = player.level().getEntity(check.vehicleId());
            if (vehicle == null || !TeleportHandler.getOtherPassengerIds(vehicle, playerId).equals(check.passengerIds())
                    || vehicle.position().distanceToSqr(check.vehiclePos()) > 1.0
                    || player.position().distanceToSqr(check.playerPos()) > 4.0
                    || !TeleportHandler.canRecoverMount(player, vehicle, check.type(), check.crossDimensional())) continue;
            player.startRiding(vehicle, true, false);
        }
    }
}
