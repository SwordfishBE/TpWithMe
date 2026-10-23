package net.tpwithme;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.compat.OpenPartiesAndClaimsCompat.TeleportType;
import net.tpwithme.config.TpWithMeConfig;
import net.tpwithme.handler.PendingMountTeleports;
import net.tpwithme.handler.RemountWatcher;
import net.tpwithme.handler.TeleportHandler;
import net.tpwithme.permission.PermissionManager;
import net.tpwithme.util.LoadedMountLookup;
import net.tpwithme.util.SafetyChecker;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.tpwithme.RegressionTests.*;

final class ReviewFollowupRegressionTests {
    static void run(GameTestHelper h, ServerPlayer player, Entity horse, Vec3 origin, Vec3 target) throws Exception {
        var cfg = TpWithMeConfig.get();
        boolean oldChorus = cfg.chorusFruitTeleport;
        try {
            for (boolean deniedPermission : new boolean[]{false, true}) {
                player.stopRiding();
                RemountWatcher.clear();
                horse.snapTo(origin);
                player.snapTo(target);
                cfg.chorusFruitTeleport = deniedPermission;
                if (deniedPermission) {
                    permissionDeniedPlayer = player.getUUID();
                    permissionDeniedNode = PermissionManager.CHORUS_FRUIT_TELEPORT_PERMISSION;
                    field(PermissionManager.class, "luckPermsActive").setBoolean(null, true);
                    check(h, !PermissionManager.canChorusFruitTeleport(player), "chorus permission fixture denies carrying");
                }
                long seed = nearbyChorusSeed(player, h.getLevel(), target);
                player.snapTo(target);
                PendingMountTeleports.schedule(player, horse, target);
                RemountWatcher.schedule(player, horse, TeleportType.GENERAL, false);
                player.getRandom().setSeed(seed);
                boolean result = chorus(h.getLevel(), player, 16);
                Vec3 chorusPosition = player.position();
                check(h, result && player.getVehicle() == null && chorusPosition.distanceToSqr(target) > 0.25
                                && chorusPosition.distanceToSqr(target) < 4,
                        "vanilla chorus completes nearby landing (permission denied=" + deniedPermission + ")");
                check(h, pending().isEmpty() && terrainWaits().isEmpty(), "successful vanilla chorus clears both old queues");
                PendingMountTeleports.tick(h.getLevel().getServer());
                check(h, player.getVehicle() == null && player.position().equals(chorusPosition)
                                && horse.position().equals(origin),
                        "stale general transfer cannot undo vanilla chorus landing");

                Vec3 blocked = h.absoluteVec(new Vec3(22.5, 5, 22.5));
                player.snapTo(blocked);
                PendingMountTeleports.schedule(player, horse, blocked);
                RemountWatcher.schedule(player, horse, TeleportType.GENERAL, false);
                Object terrainWait = terrainWaits().get(player.getUUID());
                Object recovery = pending().get(player.getUUID());
                check(h, !chorus(h.getLevel(), player, 0), "vanilla chorus fails inside solid terrain");
                check(h, terrainWaits().get(player.getUUID()) == terrainWait
                                && pending().get(player.getUUID()) == recovery,
                        "failed vanilla chorus preserves existing wait and recovery");
                RemountWatcher.clear();
                permissionDeniedPlayer = null;
                permissionDeniedNode = null;
                PermissionManager.refreshState();
            }
            player.snapTo(origin);
            BlockPos flower = BlockPos.containing(origin);
            var previous = h.getLevel().getBlockState(flower);
            try {
                check(h, SafetyChecker.isSafe(horse, h.getLevel(), origin), "rose fixture starts on safe ground");
                h.getLevel().setBlockAndUpdate(flower, Blocks.WITHER_ROSE.defaultBlockState());
                check(h, !SafetyChecker.isSafe(horse, h.getLevel(), origin), "wither rose landing is unsafe");
                check(h, SafetyChecker.findSafePosition(horse, h.getLevel(), origin, 0) == null,
                        "zero-radius search rejects wither rose");
                Vec3 alternative = SafetyChecker.findSafePosition(horse, h.getLevel(), origin, 4);
                check(h, alternative != null && !alternative.equals(origin)
                                && SafetyChecker.isSafe(horse, h.getLevel(), alternative),
                        "safety search finds safe ground away from wither rose");
            } finally {
                h.getLevel().setBlockAndUpdate(flower, previous);
            }
            hiddenRecovery(h, player, horse, origin);
            centeredLanding(h);
        } finally {
            cfg.chorusFruitTeleport = oldChorus;
            permissionDeniedPlayer = null;
            permissionDeniedNode = null;
            PermissionManager.refreshState();
            RemountWatcher.clear();
            player.stopRiding();
            horse.snapTo(origin);
            player.snapTo(origin);
        }
    }

    private static void centeredLanding(GameTestHelper h) {
        var level = h.getLevel();
        var previous = new java.util.HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        var pig = h.spawn(net.minecraft.world.entity.EntityTypes.PIG, new Vec3(13.5, 1, 16.5));
        pig.setNoAi(true);
        pig.setNoGravity(true);
        try {
            for (int x = 9; x <= 17; x++) for (int z = 12; z <= 20; z++) {
                for (int y = 1; y <= 6; y++) {
                    var block = h.absolutePos(new BlockPos(x, y, z));
                    previous.put(block, level.getBlockState(block));
                    level.setBlockAndUpdate(block, (x == 13 ? Blocks.AIR : Blocks.STONE).defaultBlockState());
                }
            }
            Vec3 target = h.absoluteVec(new Vec3(13.1, 1, 16.5));
            check(h, !SafetyChecker.isSafe(pig, level, target), "off-center pig landing overlaps corridor wall");
            Vec3 landing = SafetyChecker.findSafePosition(pig, level, target, 4);
            check(h, landing != null && SafetyChecker.isSafe(pig, level, landing),
                    "nearby search centers mount in safe one-block corridor");
            check(h, Math.abs(landing.x - h.absoluteVec(new Vec3(13.5, 1, 16.5)).x) < 1.0E-6,
                    "centered landing preserves corridor clearance");
        } finally {
            pig.discard();
            previous.forEach(level::setBlockAndUpdate);
        }
    }

    @SuppressWarnings("unchecked")
    private static void hiddenRecovery(GameTestHelper h, ServerPlayer player, Entity horse, Vec3 origin) throws Exception {
        var hiddenChunk = horse.chunkPosition();
        try {
            EntityVisibilityTestSupport.setVisibility(h.getLevel(), hiddenChunk, Visibility.HIDDEN);
            check(h, h.getLevel().getEntity(horse.getUUID()) == null
                            && LoadedMountLookup.find(h.getLevel(), horse.getUUID(), origin) == horse
                            && TeleportHandler.canRecoverMount(player, horse, TeleportType.GENERAL, false),
                    "loaded hidden mount remains eligible for recovery");
            for (boolean moved : new boolean[]{false, true}) {
                player.stopRiding();
                horse.snapTo(origin);
                player.snapTo(origin);
                RemountWatcher.schedule(player, horse, TeleportType.GENERAL, false);
                Map<UUID, Object> recovery = (Map<UUID, Object>) (Map<?, ?>) pending();
                var constructor = recovery.get(player.getUUID()).getClass().getDeclaredConstructors()[0];
                constructor.setAccessible(true);
                recovery.put(player.getUUID(), constructor.newInstance(horse.getUUID(), h.getLevel().dimension(),
                        origin, origin, TeleportType.GENERAL, false, Set.of(), h.getLevel().getServer().getTickCount()));
                if (moved) horse.snapTo(origin.add(2, 0, 0));
                Vec3 before = horse.position();
                var tick = RemountWatcher.class.getDeclaredMethod("onTick", MinecraftServer.class);
                tick.setAccessible(true);
                tick.invoke(null, h.getLevel().getServer());
                check(h, (moved ? player.getVehicle() == null : player.getVehicle() == horse) && pending().isEmpty(),
                        moved ? "moved hidden mount is not recovered" : "nearby hidden mount is recovered");
                check(h, horse.position().equals(before), "hidden recovery never teleports mount");
            }
        } finally {
            EntityVisibilityTestSupport.setVisibility(h.getLevel(), hiddenChunk, Visibility.TICKING);
        }
    }

    private static Map<?, ?> terrainWaits() throws Exception {
        return (Map<?, ?>) field(PendingMountTeleports.class, "pending").get(null);
    }

    private static boolean chorus(ServerLevel level, ServerPlayer player, float diameter) {
        return new TeleportRandomlyConsumeEffect(diameter, false)
                .apply(level, new ItemStack(Items.CHORUS_FRUIT), player);
    }

    private static long nearbyChorusSeed(ServerPlayer player, ServerLevel level, Vec3 target) {
        for (long seed = 0; seed < 10000; seed++) {
            player.snapTo(target);
            long candidateSeed = seed * 0x9E3779B97F4A7C15L;
            player.getRandom().setSeed(candidateSeed);
            boolean moved = chorus(level, player, 16);
            double distance = player.position().distanceToSqr(target);
            if (moved && distance > 0.25 && distance < 4) return candidateSeed;
        }
        throw new AssertionError("no deterministic nearby chorus candidate");
    }
}
