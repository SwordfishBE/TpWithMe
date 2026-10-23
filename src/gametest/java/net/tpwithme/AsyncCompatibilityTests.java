package net.tpwithme;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static net.tpwithme.RegressionTests.*;

/** Observes genuine Async worker ticks; no entity types are forced onto the main thread. */
public final class AsyncCompatibilityTests {
    private static final Set<UUID> watched = ConcurrentHashMap.newKeySet();
    private static final Set<String> workerThreads = ConcurrentHashMap.newKeySet();
    private static final AtomicReference<UUID> removed = new AtomicReference<>();
    private static final AtomicBoolean deferred = new AtomicBoolean();
    private static final AtomicBoolean offThreadDrop = new AtomicBoolean();
    private static final AtomicInteger drops = new AtomicInteger();
    private static volatile Map<?, ?> requests;
    private static volatile Vec3 dropPosition;
    private static volatile boolean active;
    private static boolean listenerRegistered;

    public static boolean onMobTick(Mob mob) {
        if (!active || !watched.contains(mob.getUUID()) || mob.level().getServer().isSameThread()) return false;
        workerThreads.add(Thread.currentThread().getName());
        if (requests.containsKey(mob.getUUID()) && removed.compareAndSet(null, mob.getUUID())) {
            dropPosition = mob.position();
            mob.discard();
            deferred.set(requests.containsKey(mob.getUUID()));
            return true;
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void run(GameTestHelper h, Runnable finished) {
        if (!FabricLoader.getInstance().isModLoaded("async") || !FabricLoader.getInstance().isModLoaded("leashedteleport")) {
            finished.run();
            return;
        }
        var level = h.getLevel();
        var server = level.getServer();
        var player = h.makeMockServerPlayerInLevel();
        player.setNoGravity(true);
        var cows = new ArrayList<Mob>();
        var chunks = new ArrayList<ChunkPos>();
        var cleaned = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) return;
            active = false;
            try {
                Class.forName("net.leashedteleport.handler.PendingLeashTeleports")
                        .getMethod("cancelPlayer", MinecraftServer.class, UUID.class).invoke(null, server, player.getUUID());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            } finally {
                for (Mob cow : cows) cow.discard();
                for (ChunkPos chunk : chunks) level.getChunkSource().updateChunkForced(chunk, false);
                server.getPlayerList().remove(player);
                watched.clear();
                workerThreads.clear();
                requests = null;
                dropPosition = null;
            }
        };
        h.runBeforeTestEnd(cleanup);
        try {
            var processor = Class.forName("com.axalotl.async.common.ParallelProcessor");
            var config = Class.forName("com.axalotl.async.common.config.AsyncConfig");
            check(h, !config.getField("disabled").getBoolean(null), "Async is enabled, not merely installed");
            TpWithMe.LOGGER.info("Async test options: asyncSpawn={}, asyncRandomTicks={}",
                    config.getField("enableAsyncSpawn").getBoolean(null), config.getField("enableAsyncRandomTicks").getBoolean(null));
            check(h, (int) processor.getMethod("getPoolSize").invoke(null) > 0, "Async worker pool is running");
            var shouldSync = processor.getMethod("shouldTickSynchronously", Entity.class);
            check(h, (boolean) shouldSync.invoke(null, player), "Async keeps player teleport entry points synchronous");
            var pearl = new ThrownEnderpearl(level, player, new ItemStack(Items.ENDER_PEARL));
            try {
                check(h, (boolean) shouldSync.invoke(null, pearl), "Async keeps actual pearl projectiles synchronous");
            } finally {
                pearl.discard();
            }
            var pending = Class.forName("net.leashedteleport.handler.PendingLeashTeleports");
            requests = (Map<?, ?>) field(pending, "pending").get(null);
            var type = (Class<? extends Enum>) Class.forName("net.leashedteleport.compat.OpenPartiesAndClaimsCompat$TeleportType");
            var general = Enum.valueOf(type, "GENERAL");
            var schedule = pending.getMethod("schedule", ServerPlayer.class, Mob.class, type);
            Vec3 min = h.absoluteVec(new Vec3(3, 15, 3));
            Vec3 max = min.add(18, 0, 18);
            for (int x = (int) Math.floor(min.x) >> 4; x <= (int) Math.floor(max.x) >> 4; x++) {
                for (int z = (int) Math.floor(min.z) >> 4; z <= (int) Math.floor(max.z) >> 4; z++) {
                    var chunk = new ChunkPos(x, z);
                    level.getChunkSource().updateChunkForced(chunk, true);
                    chunks.add(chunk);
                }
            }
            player.snapTo(new Vec3(-Math.abs(min.x) - 90000.5, 80, 200000.5));
            for (int i = 0; i < 256; i++) {
                var cow = h.spawn(EntityTypes.COW, new Vec3(3 + (i % 16) * 1.1, 15, 3 + (i / 16) * 1.1));
                cow.setNoAi(true);
                cow.setNoGravity(true);
                cow.noPhysics = true;
                cow.setPermanentlyInvulnerable(true);
                cows.add(cow);
                watched.add(cow.getUUID());
                if (i % 8 == 0) {
                    cow.setLeashedTo(player, true);
                    schedule.invoke(null, player, cow, general);
                }
            }
            check(h, !(boolean) shouldSync.invoke(null, cows.getFirst()), "Async is allowed to tick ordinary cows on workers");
            if (!listenerRegistered) {
                ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
                    Vec3 pos = dropPosition;
                    if (active && pos != null && entity instanceof ItemEntity item && item.getItem().is(Items.LEAD)
                            && entity.position().distanceToSqr(pos) < 0.01) {
                        drops.incrementAndGet();
                        if (!world.getServer().isSameThread()) offThreadDrop.set(true);
                    }
                });
                listenerRegistered = true;
            }
            removed.set(null);
            deferred.set(false);
            offThreadDrop.set(false);
            drops.set(0);
            active = true;
            h.runAfterDelay(20, () -> {
                check(h, !workerThreads.isEmpty(), "ordinary mobs actually ticked on Async workers: " + workerThreads);
                check(h, removed.get() != null, "pending leashed mob was removed during a real Async worker tick");
                check(h, deferred.get(), "worker unload does not mutate the main-thread pending request");
                check(h, !requests.containsKey(removed.get()), "main-thread tick consumes queued worker removal");
                check(h, drops.get() == 1 && !offThreadDrop.get(), "worker removal returns exactly one lead on the main thread");
                cleanup.run();
                finished.run();
            });
        } catch (Exception e) {
            cleanup.run();
            throw new RuntimeException(e);
        }
    }
}
