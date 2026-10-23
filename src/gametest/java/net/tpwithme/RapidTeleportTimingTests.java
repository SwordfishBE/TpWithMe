package net.tpwithme;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.handler.PendingMountTeleports;
import net.tpwithme.handler.RemountWatcher;

import java.util.Map;

import static net.tpwithme.RegressionTests.*;

/** A new home must not inherit the almost-expired timeout of an earlier home. */
final class RapidTeleportTimingTests {
    static void run(GameTestHelper h, Runnable finished) {
        var level = h.getLevel();
        var server = level.getServer();
        var sourceTicket = sourceTicketType();
        check(h, sourceTicket.timeout() == 120 && sourceTicket.canExpireIfUnloaded()
                        && sourceTicket.doesLoad() && !sourceTicket.doesSimulate() && !sourceTicket.persist(),
                "source guard has a bounded nonpersistent timeout even while terrain is not ready");
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.setNoGravity(true);
        var camel = h.spawn(EntityTypes.CAMEL, new Vec3(7.5, 1, 7.5));
        camel.setNoAi(true);
        camel.setNoGravity(true);
        camel.standUpInstantly();
        camel.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
        var id = camel.getUUID();
        Vec3 homeA = camel.position();
        Vec3 homeB = new Vec3(-Math.abs(homeA.x) - 16384.3, 66, 705.3);
        int started = server.getTickCount();
        Entity[] reloaded = {null};
        var initialLoading = new java.util.ArrayList<net.minecraft.world.level.ChunkPos>();
        Runnable releaseInitial = () -> {
            for (var chunk : initialLoading) level.getChunkSource().updateChunkForced(chunk, false);
            initialLoading.clear();
        };
        h.runBeforeTestEnd(() -> {
            RemountWatcher.cancel(player.getUUID());
            player.stopRiding();
            camel.discard();
            if (reloaded[0] != null) reloaded[0].discard();
            releaseInitial.run();
            server.getPlayerList().remove(player);
        });
        mount(player, camel);
        home(player, homeB);
        camel.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        check(h, waiting().containsKey(player.getUUID()), "timing test starts with deferred mount at B");
        h.runAfterDelay(98, () -> {
            check(h, waiting().containsKey(player.getUUID()) && server.getTickCount() - started < 100,
                    "source unload preserves pending mount before five-second deadline");
            home(player, homeA);
            int returnedAt = server.getTickCount();
            h.runAfterDelay(12, () -> {
                check(h, waiting().containsKey(player.getUUID()) && server.getTickCount() - started >= 100,
                        "return home has fresh terrain budget instead of immediate old timeout");
                var restored = EntityTypes.CAMEL.create(level, EntitySpawnReason.LOAD);
                restored.setUUID(id);
                restored.setNoAi(true);
                restored.setNoGravity(true);
                restored.standUpInstantly();
                restored.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
                restored.snapTo(homeA);
                reloaded[0] = restored;
                check(h, level.addFreshEntity(restored), "source entity can reload with original mount UUID");
                h.runAfterDelay(5, () -> {
                    check(h, player.getVehicle() == restored && restored.position().distanceToSqr(homeA) < 0.01
                                    && server.getTickCount() - returnedAt < 100 && !waiting().containsKey(player.getUUID()),
                            "A-B-A completes after source reload across multiple real server ticks");
                    // Supply the first arrival's client-loading fixture only. Later returns rely on the production guard.
                    for (int x = (int) Math.floor(homeB.x - 6) >> 4; x <= (int) Math.floor(homeB.x + 6) >> 4; x++) {
                        for (int z = (int) Math.floor(homeB.z - 6) >> 4; z <= (int) Math.floor(homeB.z + 6) >> 4; z++) {
                            var chunk = new net.minecraft.world.level.ChunkPos(x, z);
                            initialLoading.add(chunk);
                            level.getChunkSource().updateChunkForced(chunk, true);
                        }
                    }
                    for (int x = (int) Math.floor(homeB.x) - 6; x <= (int) Math.floor(homeB.x) + 6; x++) {
                        for (int z = (int) Math.floor(homeB.z) - 6; z <= (int) Math.floor(homeB.z) + 6; z++) {
                            level.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, 65, z),
                                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                            for (int y = 66; y <= 74; y++) level.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, y, z),
                                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                        }
                    }
                    repeatLoadedHomes(h, player, id, homeA, homeB, 3, releaseInitial, () -> {
                        h.runAfterDelay(130, () -> {
                            check(h, !hasSourceTicket(h, homeB), "source chunk guard expires automatically after rapid teleports stop");
                            finished.run();
                        });
                    });
                });
            });
        });
    }

    private static void repeatLoadedHomes(GameTestHelper h, ServerPlayer player, java.util.UUID id,
                                          Vec3 homeA, Vec3 homeB, int remaining, Runnable releaseInitial, Runnable finished) {
        if (remaining == 0) {
            finished.run();
            return;
        }
        home(player, homeB);
        h.runAfterDelay(3, () -> {
            h.assertTrue(h.getLevel().getChunkSource().getChunkNow((int) Math.floor(homeB.x) >> 4,
                    (int) Math.floor(homeB.z) >> 4) != null, "loaded-repeat destination fixture remains readable");
            if (!arrivedSafely(player, id, homeB)) {
                TpWithMe.LOGGER.info("Rapid timing mismatch: vehicle={}, position={}, pending={}, sourceTicket={}",
                        player.getVehicle(), player.position(), waiting(), hasSourceTicket(h, homeB));
            }
            check(h, arrivedSafely(player, id, homeB),
                    "mounted home reaches B with three server ticks between rapid jumps: " + remaining);
            home(player, homeA);
            if (remaining == 3) {
                check(h, hasSourceTicket(h, homeB), "leaving B retains its already loaded source chunk with a temporary ticket");
                releaseInitial.run();
            }
            h.runAfterDelay(3, () -> {
                check(h, arrivedSafely(player, id, homeA)
                                && !waiting().containsKey(player.getUUID()),
                        "mounted home returns to A without stale request after real ticks: " + remaining);
                repeatLoadedHomes(h, player, id, homeA, homeB, remaining - 1, releaseInitial, finished);
            });
        });
    }

    private static boolean arrivedSafely(ServerPlayer player, java.util.UUID id, Vec3 target) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null || !vehicle.getUUID().equals(id)) return false;
        Vec3 delta = vehicle.position().subtract(target);
        double radius = net.tpwithme.config.TpWithMeConfig.get().safetySearchRadius + 1.0E-6;
        return Math.abs(delta.x) <= radius && Math.abs(delta.y) <= radius && Math.abs(delta.z) <= radius
                && net.tpwithme.util.SafetyChecker.isSafe(vehicle, player.level(), vehicle.position());
    }

    private static boolean hasSourceTicket(GameTestHelper h, Vec3 pos) {
        try {
            var storage = (net.minecraft.world.level.TicketStorage) field(net.minecraft.server.level.ChunkMap.class, "ticketStorage")
                    .get(h.getLevel().getChunkSource().chunkMap);
            var type = sourceTicketType();
            var chunk = new net.minecraft.world.level.ChunkPos((int) Math.floor(pos.x) >> 4, (int) Math.floor(pos.z) >> 4);
            return storage.getTickets(chunk.pack()).stream().anyMatch(ticket -> ticket.getType() == type);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<?, ?> waiting() {
        try { return (Map<?, ?>) field(PendingMountTeleports.class, "pending").get(null); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static net.minecraft.server.level.TicketType sourceTicketType() {
        try {
            return (net.minecraft.server.level.TicketType) field(net.tpwithme.util.MountSourceChunkGuard.class, "SOURCE").get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void home(ServerPlayer player, Vec3 target) {
        try {
            if (FabricLoader.getInstance().isModLoaded("essential_commands")) CamelTeleportRegressionTests.essentialHome(player, target);
            else player.teleport(transition(player, target));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
