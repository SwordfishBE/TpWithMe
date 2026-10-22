package net.tpwithme;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.config.TpWithMeConfig;
import net.tpwithme.handler.PendingMountTeleports;
import net.tpwithme.handler.RemountWatcher;
import net.tpwithme.handler.TeleportHandler;
import net.tpwithme.permission.PermissionManager;
import net.tpwithme.util.SafetyChecker;

import java.util.Map;
import java.util.Set;

import static net.tpwithme.RegressionTests.*;

final class MountedTeleportRegressionTests {
    static void run(GameTestHelper h, ServerPlayer player, Entity horse, Vec3 origin, Vec3 target) throws Exception {
        var slab = Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
        h.setBlock(5, 0, 5, slab);
        check(h, SafetyChecker.isSafe(horse, h.getLevel(), origin), "dry top slab supports mount");
        h.setBlock(5, 0, 5, slab.setValue(BlockStateProperties.WATERLOGGED, true));
        check(h, SafetyChecker.isSafe(horse, h.getLevel(), origin), "waterlogged top slab supports mount above water");
        check(h, origin.equals(SafetyChecker.findSafePosition(horse, h.getLevel(), origin, 0)),
                "waterlogged support succeeds with exact-only safety");
        h.setBlock(5, 0, 5, Blocks.STONE);

        var level = h.getLevel();
        var server = level.getServer();
        var visitor = h.makeMockServerPlayerInLevel();
        var newcomer = h.makeMockServerPlayerInLevel();
        var camel = h.spawn(EntityTypes.CAMEL, new Vec3(7.5, 1, 7.5));
        camel.setNoAi(true);
        camel.setNoGravity(true);
        Vec3 camelOrigin = camel.position();
        var cfg = TpWithMeConfig.get();
        try {
            mount(player, camel);
            visitor.snapTo(camelOrigin);
            check(h, visitor.startRiding(camel, true, false), "second rider boards test camel");
            permissionDeniedPlayer = visitor.getUUID();
            field(PermissionManager.class, "luckPermsActive").setBoolean(null, true);
            permissionDeniedNode = PermissionManager.USE_PERMISSION;
            player.teleport(transition(player, target));
            check(h, camel.position().equals(camelOrigin) && visitor.getVehicle() == camel && player.getVehicle() == null,
                    "general carry checks second rider use permission");

            mount(player, camel);
            permissionDeniedNode = PermissionManager.ENDER_PEARL_TELEPORT_PERMISSION;
            TeleportHandler.handleMountedPearlTeleport(player, transition(player, target));
            check(h, camel.position().equals(camelOrigin) && visitor.getVehicle() == camel && player.getVehicle() == null,
                    "pearl carry checks second rider pearl permission");

            mount(player, camel);
            player.snapTo(camelOrigin.add(0, 2.5, 0));
            permissionDeniedNode = PermissionManager.CHORUS_FRUIT_TELEPORT_PERMISSION;
            check(h, Boolean.FALSE.equals(TeleportHandler.handleMountedChorusFruitTeleport(level, player, 0)),
                    "chorus carry checks second rider chorus permission");
            check(h, camel.position().equals(camelOrigin) && visitor.getVehicle() == camel,
                    "denied chorus does not move any rider");

            permissionDeniedNode = PermissionManager.CROSS_DIMENSIONAL_TELEPORT_PERMISSION;
            cfg.checkSafety = false;
            var nether = server.getLevel(Level.NETHER);
            player.teleport(new TeleportTransition(nether, new Vec3(0.5, 80, 0.5), Vec3.ZERO, 0, 0, Set.of(), TeleportTransition.DO_NOTHING));
            check(h, player.level() == nether && camel.level() == level && visitor.level() == level,
                    "cross-dimensional carry checks second rider dimension permission");
            player.teleport(new TeleportTransition(level, camelOrigin, Vec3.ZERO, 0, 0, Set.of(), TeleportTransition.DO_NOTHING));
            cfg.checkSafety = true;
            permissionDeniedPlayer = null;
            permissionDeniedNode = null;
            PermissionManager.refreshState();

            mount(player, camel);
            int chunkX = BlockPos.containing(-Math.abs(origin.x) - 8192, 66, 200).getX() >> 4;
            int chunkZ = 200 >> 4;
            int baseX = chunkX << 4, baseZ = chunkZ << 4;
            Vec3 blocked = new Vec3(baseX + 1.5, 66, baseZ + 8.5);
            player.teleport(transition(player, blocked));
            var waiting = waiting();
            check(h, waiting.size() == 1, "two-rider cold destination queues transfer");
            PendingMountTeleports.tick(server);
            check(h, waiting.size() == 1 && visitor.getVehicle() == camel,
                    "original second rider does not cancel terrain wait");
            var passengerIds = field(waiting.values().iterator().next().getClass(), "passengerIds");
            check(h, ((Set<?>) passengerIds.get(waiting.values().iterator().next())).equals(Set.of(visitor.getUUID())),
                    "terrain wait snapshots only other passenger UUIDs");

            // Direct chunk edits avoid loading adjacent chunks through neighbor updates.
            var loadedChunk = level.getChunk(chunkX, chunkZ);
            for (int x = baseX; x < baseX + 16; x++) for (int z = baseZ; z < baseZ + 16; z++) {
                loadedChunk.setBlockState(new BlockPos(x, 65, z), Blocks.STONE.defaultBlockState(), 0);
                for (int y = 66; y <= 72; y++) loadedChunk.setBlockState(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 0);
            }
            for (int y = 66; y <= 71; y++) loadedChunk.setBlockState(new BlockPos(baseX + 1, y, baseZ + 8), Blocks.STONE.defaultBlockState(), 0);
            check(h, !SafetyChecker.areSearchChunksLoaded(camel, level, blocked, 2), "outer search chunk remains unavailable");
            check(h, !SafetyChecker.isSafe(camel, level, blocked)
                            && SafetyChecker.findSafePosition(camel, level, blocked, 2) != null,
                    "safe fallback available despite blocked exact target and missing outer chunk");
            PendingMountTeleports.tick(server);
            check(h, waiting.isEmpty() && player.getVehicle() == camel && visitor.getVehicle() == camel
                            && SafetyChecker.isSafe(camel, level, camel.position()),
                    "deferred transfer takes both permitted riders to loaded safe fallback");
            // Recovery runs in an entity-ticking arena, independently of remote chunk promotion.
            player.teleport(transition(player, camelOrigin));
            recoverWithCoRider(h, player, camel, visitor);

            Vec3 before = camel.position();
            Vec3 cold = blocked.add(-4096, 0, 0);
            player.teleport(transition(player, cold));
            newcomer.snapTo(before);
            check(h, newcomer.startRiding(camel, true, false), "new rider boards while transfer waits");
            PendingMountTeleports.tick(server);
            check(h, waiting.isEmpty() && camel.position().equals(before) && player.getVehicle() == null,
                    "newly boarding passenger cancels terrain wait without moving mount");
            newcomer.stopRiding();

            // A permission change during the wait must still deny the original co-rider.
            player.snapTo(cold);
            PendingMountTeleports.schedule(player, camel, cold);
            permissionDeniedPlayer = visitor.getUUID();
            permissionDeniedNode = PermissionManager.USE_PERMISSION;
            field(PermissionManager.class, "luckPermsActive").setBoolean(null, true);
            cfg.checkSafety = false;
            PendingMountTeleports.tick(server);
            check(h, waiting.isEmpty() && camel.position().equals(before) && visitor.getVehicle() == camel,
                    "deferred transfer rechecks original co-rider permission changes");
        } finally {
            permissionDeniedPlayer = null;
            permissionDeniedNode = null;
            PermissionManager.refreshState();
            cfg.checkSafety = true;
            camel.ejectPassengers();
            camel.discard();
            server.getPlayerList().remove(visitor);
            server.getPlayerList().remove(newcomer);
            RemountWatcher.clear();
            player.snapTo(origin);
        }
    }

    private static Map<?, ?> waiting() throws Exception {
        return (Map<?, ?>) field(PendingMountTeleports.class, "pending").get(null);
    }

    @SuppressWarnings("unchecked")
    private static void recoverWithCoRider(GameTestHelper h, ServerPlayer player, Entity camel, ServerPlayer visitor) throws Exception {
        player.stopRiding();
        player.snapTo(camel.position());
        RemountWatcher.schedule(player, camel, net.tpwithme.compat.OpenPartiesAndClaimsCompat.TeleportType.GENERAL, false);
        var recovery = (Map<java.util.UUID, Object>) pending();
        var constructor = recovery.get(player.getUUID()).getClass().getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        recovery.put(player.getUUID(), constructor.newInstance(camel.getUUID(), player.level().dimension(),
                camel.position(), player.position(), net.tpwithme.compat.OpenPartiesAndClaimsCompat.TeleportType.GENERAL,
                false, Set.of(visitor.getUUID()), player.level().getServer().getTickCount()));
        var tick = RemountWatcher.class.getDeclaredMethod("onTick", net.minecraft.server.MinecraftServer.class);
        tick.setAccessible(true);
        tick.invoke(null, player.level().getServer());
        check(h, player.getVehicle() == camel && visitor.getVehicle() == camel,
                "post-teleport remount recovery preserves original co-rider");
    }
}
