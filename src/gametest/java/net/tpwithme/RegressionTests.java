package net.tpwithme;

import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.util.TriState;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.compat.OpenPartiesAndClaimsCompat;
import net.tpwithme.compat.OpenPartiesAndClaimsCompat.TeleportType;
import net.tpwithme.config.TpWithMeConfig;
import net.tpwithme.handler.RemountWatcher;
import net.tpwithme.handler.PendingMountTeleports;
import net.tpwithme.handler.TeleportHandler;
import net.tpwithme.permission.PermissionManager;
import net.tpwithme.util.ModrinthUpdateChecker;
import net.tpwithme.util.SafetyChecker;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class RegressionTests {
    private static boolean permissionFixture;
    static java.util.UUID permissionDeniedPlayer;
    static String permissionDeniedNode;
    private static int assertions;

    @GameTest(structure = "tpwithme-test:arena", maxTicks = 400)
    public void regressions(GameTestHelper h) throws Exception {
        assertions = 0;
        var oldConfig = TpWithMeConfig.get();
        var cfg = new TpWithMeConfig();
        cfg.requireSaddle = false;
        cfg.applyTeleportProtection = false;
        field(TpWithMeConfig.class, "instance").set(null, cfg);
        h.runBeforeTestEnd(() -> {
            try { field(TpWithMeConfig.class, "instance").set(null, oldConfig); }
            catch (Exception e) { throw new RuntimeException(e); }
            permissionFixture = false;
            permissionDeniedPlayer = null;
            permissionDeniedNode = null;
            PermissionManager.refreshState();
        });
        PermissionCheckEvent.EVENT.register((source, permission) -> {
            if (permissionDeniedPlayer != null) {
                return source instanceof net.minecraft.commands.CommandSourceStack commandSource
                        && commandSource.getEntity() instanceof ServerPlayer checked && checked.getUUID().equals(permissionDeniedPlayer)
                        && permission.equals(permissionDeniedNode) ? TriState.FALSE : TriState.TRUE;
            }
            return permissionFixture
                    ? (permission.equals(PermissionManager.USE_PERMISSION) ? TriState.TRUE : TriState.FALSE)
                    : TriState.DEFAULT;
        });
        // Retain the local mock-client fixture before any remote teleport can queue its unload.
        var recoveryBlock = h.absolutePos(new BlockPos(5, 1, 5));
        var recoveryChunk = new net.minecraft.world.level.ChunkPos(recoveryBlock.getX() >> 4, recoveryBlock.getZ() >> 4);
        h.getLevel().getChunkSource().updateChunkForced(recoveryChunk, true);
        Runnable releaseRecoveryChunk = () -> h.getLevel().getChunkSource().updateChunkForced(recoveryChunk, false);
        h.runBeforeTestEnd(releaseRecoveryChunk);
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) h.setBlock(x, 0, z, Blocks.STONE);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        h.runBeforeTestEnd(() -> h.getLevel().getServer().getPlayerList().remove(player));
        var horse = h.spawn(EntityTypes.HORSE, new Vec3(5.5, 1, 5.5));
        horse.setNoAi(true);
        horse.setNoGravity(true);
        Vec3 origin = horse.position();
        Vec3 target = h.absoluteVec(new Vec3(15.5, 1, 5.5));

        mount(player, horse);
        cfg.enderPearlTeleport = false;
        TeleportHandler.handleMountedPearlTeleport(player, transition(player, target));
        check(h, player.getVehicle() == null && horse.position().equals(origin), "disabled pearl leaves mount behind");
        check(h, player.position().distanceToSqr(target) < 0.01, "disabled pearl preserves vanilla player teleport");

        mount(player, horse);
        cfg.enderPearlTeleport = true;
        permissionFixture = true;
        field(PermissionManager.class, "luckPermsActive").setBoolean(null, true);
        check(h, PermissionManager.canUse(player) && !PermissionManager.canEnderPearlTeleport(player), "permission fixture grants use, denies pearl");
        TeleportHandler.handleMountedPearlTeleport(player, transition(player, target));
        check(h, player.getVehicle() == null && horse.position().equals(origin), "denied pearl permission leaves mount behind");
        permissionFixture = false;
        PermissionManager.refreshState();

        mount(player, horse);
        TeleportHandler.handleMountedPearlTeleport(player, transition(player, target));
        check(h, player.getVehicle() == horse && horse.position().distanceToSqr(target) < 0.01, "allowed pearl carries mount");

        TeleportHandler.handleMountedPearlTeleport(player, transition(player, target.add(0, 0.37, 0)));
        check(h, player.getVehicle() == horse && horse.position().distanceToSqr(target) < 0.01,
                "fractional pearl landing relocates rider and mount to the ground");

        cfg.checkSafety = false;
        Vec3 before = player.position();
        player.teleport(new TeleportTransition(h.getLevel(), new Vec3(3, 0, 2), Vec3.ZERO, 0, 0,
                Set.of(Relative.X, Relative.Y, Relative.Z), TeleportTransition.DO_NOTHING));
        check(h, horse.position().distanceToSqr(before.add(3, 0, 2)) < 0.01, "relative teleport uses resolved position");

        cfg.enderPearlTeleport = false;
        try {
            TeleportHandler.handleMountedPearlTeleport(player, new TeleportTransition(h.getLevel(), target,
                    Vec3.ZERO, 0, 0, Set.of(), entity -> { throw new IllegalStateException("test failure"); }));
            throw new AssertionError("expected callback exception");
        } catch (IllegalStateException expected) {
            check(h, expected.getMessage().equals("test failure"), "injected teleport failure reached callback");
        }
        check(h, ((Set<?>) field(TeleportHandler.class, "suppressedPlayers").get(null)).isEmpty(), "pearl suppression is released on exception");
        mount(player, horse);
        player.teleport(transition(player, origin));
        check(h, horse.position().distanceToSqr(origin) < 0.01, "next normal teleport succeeds after exception");

        // Reentrant transition callback must supersede the outer capture.
        player.teleport(new TeleportTransition(h.getLevel(), target, Vec3.ZERO, 0, 0, Set.of(),
                entity -> player.teleport(transition(player, target.add(1, 0, 0)))));
        check(h, horse.position().distanceToSqr(origin) < 0.01 && player.getVehicle() == null,
                "nested teleport does not replay stale outer capture");
        cfg.enderPearlTeleport = true;
        cfg.checkSafety = true;

        horse.snapTo(origin);
        mount(player, horse);
        player.snapTo(origin.add(0, 1.6, 0));
        check(h, Boolean.TRUE.equals(TeleportHandler.handleMountedChorusFruitTeleport(h.getLevel(), player, 0)),
                "chorus grounds fractional rider height on the block surface");

        crossDimension(h, player, cfg, origin);
        otherTeleports(h, player, horse, cfg, origin, target);

        safety(h, horse, origin);
        coldDestination(h, player, horse, origin);
        configAndUpdates(h);
        LeashedChorusCompatibilityTests.run(h);
        LeashedPearlCompatibilityTests.run(h);
        ReviewFollowupRegressionTests.run(h, player, horse, origin, target);
        CamelTeleportRegressionTests.run(h, player, origin, target);
        MountedTeleportRegressionTests.run(h, player, horse, origin, target);
        if (FabricLoader.getInstance().isModLoaded("openpartiesandclaims")) {
            OpacTests.run(h, player, horse, target);
            PassengerOpacTests.run(h, player, origin, target);
        } else {
            check(h, OpenPartiesAndClaimsCompat.canTeleportInto(h.getLevel(), BlockPos.containing(target), player, TeleportType.GENERAL),
                    "OPAC absent permits normal operation");
        }

        // Duplicate requests replace each other and carry no player/entity references.
        player.stopRiding();
        horse.snapTo(origin);
        player.snapTo(origin);
        for (int i = 0; i < 100; i++) RemountWatcher.schedule(player, horse, TeleportType.GENERAL, false);
        check(h, pending().size() == 1, "recovery queue deduplicates per player");
        var record = pending().values().iterator().next();
        for (var component : record.getClass().getRecordComponents()) {
            check(h, !Entity.class.isAssignableFrom(component.getType())
                    && !net.minecraft.world.level.Level.class.isAssignableFrom(component.getType())
                    && !net.minecraft.server.MinecraftServer.class.isAssignableFrom(component.getType()),
                    "recovery stores values, not world/entity references: " + component.getName());
        }
        var other = h.spawn(EntityTypes.HORSE, new Vec3(6.5, 1, 5.5));
        other.setNoAi(true);
        other.setNoGravity(true);
        player.startRiding(other, true, false);
        h.runAfterDelay(5, () -> {
            check(h, player.getVehicle() == other, "recovery does not steal player from another mount");
            check(h, pending().isEmpty(), "changed mount cancels pending recovery");
            player.stopRiding();
            player.snapTo(origin);
            RemountWatcher.schedule(player, horse, TeleportType.GENERAL, false);
            player.teleport(transition(player, target));
            check(h, pending().isEmpty(), "new teleport cancels old recovery");
            h.runAfterDelay(5, () -> {
                check(h, horse.position().distanceToSqr(origin) < 0.01, "recovery never drags old mount after second teleport");
                player.snapTo(origin);
                RemountWatcher.schedule(player, horse, TeleportType.GENERAL, false);
                cfg.enabled = false;
                h.runAfterDelay(5, () -> {
                    check(h, player.getVehicle() == null && pending().isEmpty(), "disabled mod cancels recovery");
                    cfg.enabled = true;
                    RemountWatcher.schedule(player, horse, TeleportType.GENERAL, false);
                    ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(player.connection, h.getLevel().getServer());
                    check(h, pending().isEmpty(), "disconnect clears pending recovery");
                    RemountWatcher.schedule(player, horse, TeleportType.GENERAL, false);
                    RemountWatcher.clear();
                    check(h, pending().isEmpty(), "server lifecycle cleanup drains pending queue");
                    player.snapTo(origin);
                    RemountWatcher.schedule(player, horse, TeleportType.GENERAL, false);
                    h.runAfterDelay(5, () -> {
                        check(h, player.getVehicle() == horse, "valid nearby recovery still remounts");
                        player.stopRiding();
                        player.snapTo(origin);
                        RemountWatcher.schedule(player, horse, TeleportType.GENERAL, false);
                        player.setShiftKeyDown(true);
                        h.runAfterDelay(5, () -> {
                            check(h, player.getVehicle() == null && pending().isEmpty(), "intentional sneaking cancels recovery");
                            player.setShiftKeyDown(false);
                            releaseRecoveryChunk.run();
                            ChunkUnloadCompatibilityTests.run(h);
                            AsyncCompatibilityTests.run(h, () -> RapidTeleportTimingTests.run(h, () -> {
                                TpWithMe.LOGGER.info("TPWITHME REGRESSION ASSERTIONS PASSED: {} (OPAC={})", assertions,
                                        FabricLoader.getInstance().isModLoaded("openpartiesandclaims"));
                                h.succeed();
                            }));
                        });
                    });
                });
            });
        });
    }

    static void check(GameTestHelper h, boolean value, String message) {
        h.assertTrue(value, message);
        assertions++;
        TpWithMe.LOGGER.info("PASS: {}", message);
    }

    static void mount(ServerPlayer player, Entity vehicle) {
        player.stopRiding();
        player.snapTo(vehicle.position());
        if (!player.startRiding(vehicle, true, false)) throw new AssertionError("could not mount test vehicle");
    }

    static TeleportTransition transition(ServerPlayer player, Vec3 target) {
        return new TeleportTransition(player.level(), target, Vec3.ZERO, 0, 0, Set.of(), TeleportTransition.DO_NOTHING);
    }

    static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static Map<?, ?> pending() {
        try { return (Map<?, ?>) field(RemountWatcher.class, "pending").get(null); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    private static void coldDestination(GameTestHelper h, ServerPlayer player, Entity horse, Vec3 origin) throws Exception {
        var level = h.getLevel();
        var server = level.getServer();
        Vec3 destination = new Vec3(-Math.abs(origin.x) - 4096.3, 66, 153.3);
        var chunk = BlockPos.containing(destination);
        check(h, level.getChunkSource().getChunkNow(chunk.getX() >> 4, chunk.getZ() >> 4) == null,
                "remote negative-coordinate home starts with unloaded terrain");
        horse.snapTo(origin);
        mount(player, horse);
        player.teleport(transition(player, destination));
        Map<?, ?> waiting = (Map<?, ?>) field(PendingMountTeleports.class, "pending").get(null);
        check(h, waiting.size() == 1 && horse.position().equals(origin),
                "cold home teleport queues mount instead of rejecting space");
        check(h, level.getChunkSource().getChunkNow(chunk.getX() >> 4, chunk.getZ() >> 4) == null,
                "deferred safety check does not synchronously load terrain");
        PendingMountTeleports.tick(server);
        check(h, waiting.size() == 1, "pending mount waits while terrain is unavailable");
        player.teleport(transition(player, origin));
        check(h, player.getVehicle() == horse && waiting.isEmpty(),
                "rapid return to A retains mount from pending cold B transfer");
        player.teleport(transition(player, destination));
        check(h, waiting.size() == 1 && horse.position().equals(origin),
                "rapid B-A-B keeps latest cold destination queued");
        for (var component : waiting.values().iterator().next().getClass().getRecordComponents()) {
            check(h, !Entity.class.isAssignableFrom(component.getType())
                            && !net.minecraft.world.level.Level.class.isAssignableFrom(component.getType()),
                    "terrain wait stores values instead of world/entity references: " + component.getName());
        }
        var constructor = waiting.values().iterator().next().getClass().getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        ((Map<java.util.UUID, Object>) waiting).put(player.getUUID(), constructor.newInstance(
                horse.getUUID(), level.dimension(), level.dimension(), origin, destination, Set.of(), server.getTickCount()));
        check(h, PendingMountTeleports.captureForTeleport(player) == null,
                "new teleport cannot recover an expired terrain wait");
        PendingMountTeleports.tick(server);
        check(h, waiting.isEmpty() && horse.position().equals(origin),
                "expired terrain wait clears request without moving mount");
        java.util.UUID missingId = java.util.UUID.randomUUID();
        ((Map<java.util.UUID, Object>) waiting).put(player.getUUID(), constructor.newInstance(
                missingId, level.dimension(), level.dimension(), origin, destination, Set.of(), server.getTickCount() + 100));
        PendingMountTeleports.tick(server);
        check(h, waiting.size() == 1, "temporarily unavailable mount does not immediately cancel terrain wait");
        int originalDeadline = field(waiting.values().iterator().next().getClass(), "deadline")
                .getInt(waiting.values().iterator().next());
        player.teleport(transition(player, origin));
        check(h, waiting.size() == 1 && PendingMountTeleports.isWaitingForMount(player),
                "new teleport preserves unresolved mount wait at latest destination");
        check(h, field(waiting.values().iterator().next().getClass(), "deadline")
                        .getInt(waiting.values().iterator().next()) == originalDeadline,
                "same-tick retarget keeps a full bounded destination wait");
        ((Map<java.util.UUID, Object>) waiting).put(player.getUUID(), constructor.newInstance(
                missingId, level.dimension(), level.dimension(), origin, origin, Set.of(), server.getTickCount()));
        PendingMountTeleports.tick(server);
        PendingMountTeleports.tick(server);
        check(h, waiting.isEmpty(), "unavailable mount still expires once without an unbounded wait");
        ((Map<java.util.UUID, Object>) waiting).put(player.getUUID(), constructor.newInstance(
                missingId, level.dimension(), level.dimension(), origin, origin, Set.of(), server.getTickCount() + 100));
        boolean oldPearl = TpWithMeConfig.get().enderPearlTeleport;
        try {
            TpWithMeConfig.get().enderPearlTeleport = false;
            TeleportHandler.handleMountedPearlTeleport(player, transition(player, origin));
            check(h, waiting.isEmpty(), "disabled pearl fallback cannot retarget an unresolved general mount wait");
        } finally {
            TpWithMeConfig.get().enderPearlTeleport = oldPearl;
        }
        player.snapTo(destination);
        PendingMountTeleports.schedule(player, horse, destination);
        // Test setup loads the chunks; the production queue must never do this itself.
        for (int x = chunk.getX() - 4; x <= chunk.getX() + 4; x++) {
            for (int z = chunk.getZ() - 4; z <= chunk.getZ() + 4; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 65, z), Blocks.STONE.defaultBlockState());
                for (int y = 66; y <= 72; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        level.setBlockAndUpdate(BlockPos.containing(destination), Blocks.SHORT_DRY_GRASS.defaultBlockState());
        PendingMountTeleports.tick(server);
        check(h, waiting.isEmpty() && player.getVehicle() == horse && horse.position().distanceToSqr(destination) < 0.01,
                "mount follows once flat negative-coordinate home with dry grass becomes ready");
        player.stopRiding();
        player.snapTo(destination);
        // Hidden sections still contain loaded mounts but the public UUID lookup cannot see them.
        EntityVisibilityTestSupport.setVisibility(level, new net.minecraft.world.level.ChunkPos(chunk.getX() >> 4, chunk.getZ() >> 4),
                net.minecraft.world.level.entity.Visibility.HIDDEN);
        PendingMountTeleports.schedule(player, horse, destination);
        check(h, level.getEntity(horse.getUUID()) == null && !horse.isRemoved(),
                "hidden source mount remains loaded but absent from public UUID lookup");
        check(h, PendingMountTeleports.captureForTeleport(player) == horse,
                "rapid teleport resolves loaded mount in hidden source section");
        PendingMountTeleports.tick(server);
        check(h, waiting.isEmpty() && player.getVehicle() == horse,
                "terrain wait completes with loaded mount in hidden source section");
        player.stopRiding();
        player.snapTo(destination);
        PendingMountTeleports.schedule(player, horse, destination);
        player.teleport(transition(player, origin));
        EntityVisibilityTestSupport.setVisibility(level, new net.minecraft.world.level.ChunkPos(chunk.getX() >> 4, chunk.getZ() >> 4),
                net.minecraft.world.level.entity.Visibility.TICKING);
        check(h, waiting.isEmpty() && player.getVehicle() == horse,
                "another teleport completes pending mount transfer at the latest loaded destination");
        player.stopRiding();
        PendingMountTeleports.schedule(player, horse, origin);
        ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(player.connection, server);
        check(h, waiting.isEmpty(), "disconnect clears pending terrain wait");
        PendingMountTeleports.schedule(player, horse, origin);
        RemountWatcher.clear();
        check(h, waiting.isEmpty(), "server lifecycle cleanup clears pending terrain wait");
        horse.snapTo(origin);
        player.snapTo(origin);
        PendingMountTeleports.schedule(player, horse, origin);
        player.snapTo(origin.add(3, 0, 0));
        check(h, PendingMountTeleports.captureForTeleport(player) == null,
                "new teleport cannot recover terrain wait after player walked away");
        PendingMountTeleports.tick(server);
        check(h, waiting.isEmpty() && player.getVehicle() == null,
                "walking away cancels terrain wait without moving mount");
        player.snapTo(origin);
        PendingMountTeleports.schedule(player, horse, origin);
        horse.snapTo(origin.add(2, 0, 0));
        check(h, PendingMountTeleports.captureForTeleport(player) == null,
                "new teleport cannot recover a mount that moved away");
        PendingMountTeleports.tick(server);
        check(h, waiting.isEmpty() && player.getVehicle() == null,
                "moved mount cancels terrain wait");
        horse.snapTo(origin);
        PendingMountTeleports.schedule(player, horse, origin);
        TpWithMeConfig.get().enabled = false;
        PendingMountTeleports.tick(server);
        check(h, waiting.isEmpty() && player.getVehicle() == null,
                "disabling mod cancels terrain wait");
        TpWithMeConfig.get().enabled = true;
    }

    private static void configAndUpdates(GameTestHelper h) throws Exception {
        var config = new TpWithMeConfig();
        config.safetySearchRadius = Integer.MAX_VALUE;
        config.normalize();
        check(h, config.safetySearchRadius == 4, "oversized search radius is clamped");
        config.safetySearchRadius = -1;
        config.normalize();
        check(h, config.safetySearchRadius == 0, "negative search radius is clamped");
        var method = ModrinthUpdateChecker.class.getDeclaredMethod("extractLatestVersion", String.class, String.class);
        method.setAccessible(true);
        String incompatible = "{\"version_number\":\"9.0\",\"date_published\":\"2026-10-01T00:00:00Z\",\"version_type\":\"release\",\"loaders\":[\"neoforge\"],\"game_versions\":[\"26.3\"]}";
        check(h, ((Optional<?>) method.invoke(null, "[" + incompatible + "]", "26.3")).isEmpty(), "no incompatible update fallback");
        String compatible = incompatible.replace("neoforge", "fabric");
        check(h, ((Optional<?>) method.invoke(null, "[" + incompatible + "," + compatible + "]", "26.3")).isPresent(), "compatible update is selected");
    }

    private static void crossDimension(GameTestHelper h, ServerPlayer player, TpWithMeConfig cfg, Vec3 origin) {
        var mount = h.spawn(EntityTypes.HORSE, new Vec3(4.5, 1, 4.5));
        mount.setNoAi(true);
        var id = mount.getUUID();
        mount(player, mount);
        cfg.checkSafety = false;
        var nether = h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        player.teleport(new TeleportTransition(nether, new Vec3(0.5, 80, 0.5), Vec3.ZERO, 0, 0, Set.of(), TeleportTransition.DO_NOTHING));
        check(h, player.level() == nether && player.getVehicle() != null && player.getVehicle().getUUID().equals(id),
                "cross-dimensional teleport preserves mount identity and riding");
        cfg.checkSafety = true;
        player.teleport(new TeleportTransition(h.getLevel(), origin.add(0, 0.37, 0), Vec3.ZERO, 0, 0, Set.of(), TeleportTransition.DO_NOTHING));
        check(h, player.level() == h.getLevel() && player.getVehicle() != null && player.getVehicle().getUUID().equals(id),
                "return from another dimension remounts the transferred entity");
        var returnedMount = player.getVehicle();
        check(h, returnedMount.position().distanceToSqr(origin) < 0.01,
                "cross-dimensional destination uses safe ground correction");
        cfg.crossDimensionalTeleport = false;
        player.teleport(new TeleportTransition(nether, new Vec3(0.5, 80, 0.5), Vec3.ZERO, 0, 0, Set.of(), TeleportTransition.DO_NOTHING));
        check(h, player.level() == nether && player.getVehicle() == null && returnedMount.level() == h.getLevel(),
                "disabled cross-dimensional carry leaves mount in original dimension");
        player.teleport(new TeleportTransition(h.getLevel(), origin, Vec3.ZERO, 0, 0, Set.of(), TeleportTransition.DO_NOTHING));
        cfg.crossDimensionalTeleport = true;
        player.stopRiding();
        RemountWatcher.cancel(player.getUUID());
        returnedMount.discard();
        cfg.checkSafety = true;
    }

    private static void otherTeleports(GameTestHelper h, ServerPlayer player, Entity horse,
                                       TpWithMeConfig cfg, Vec3 origin, Vec3 target) throws Exception {
        horse.snapTo(origin);
        mount(player, horse);
        h.setBlock(15, 1, 5, Blocks.SHORT_GRASS);
        player.teleportTo(h.getLevel(), target.x, target.y + 0.37, target.z, Set.of(), 0, 0, true);
        check(h, player.getVehicle() == horse && horse.position().distanceToSqr(target) < 0.01,
                "command teleportTo path adjusts fractional destination through short grass");
        h.setBlock(15, 1, 5, Blocks.AIR);

        horse.snapTo(origin);
        mount(player, horse);
        LeashedPearlCompatibilityTests.impact(h, player, target);
        check(h, player.getVehicle() == horse && horse.position().distanceToSqr(target) < 0.01,
                "real pearl impact preserves mounted teleport with or without LeashedTeleport");

        cfg.safetySearchRadius = 0;
        mount(player, horse);
        player.teleport(transition(player, origin.add(0, 0.37, 0)));
        check(h, player.getVehicle() == null && horse.position().distanceToSqr(target) < 0.01,
                "general teleport leaves mount behind when exact-only destination lacks support");

        horse.snapTo(origin);
        mount(player, horse);
        h.setBlock(5, 1, 5, Blocks.SHORT_GRASS);
        player.snapTo(origin.add(0, 1.6, 0));
        var chorus = new net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect(0, true);
        check(h, chorus.apply(h.getLevel(), net.minecraft.world.item.ItemStack.EMPTY, player)
                        && player.getVehicle() == horse && horse.position().distanceToSqr(origin) < 0.01,
                "chorus consume mixin lands through grass with exact-only safety");

        h.setBlock(5, 1, 5, Blocks.STONE_SLAB);
        player.snapTo(origin.add(0, 2.1, 0));
        check(h, chorus.apply(h.getLevel(), net.minecraft.world.item.ItemStack.EMPTY, player)
                        && player.getVehicle() == horse
                        && horse.position().distanceToSqr(origin.add(0, 0.5, 0)) < 0.01,
                "chorus consume mixin grounds on slab surface even with search disabled");
        h.setBlock(5, 1, 5, Blocks.AIR);
        h.setBlock(5, 0, 5, Blocks.GOLD_BLOCK);
        Vec3 beforeDeniedChorus = horse.position();
        check(h, !chorus.apply(h.getLevel(), net.minecraft.world.item.ItemStack.EMPTY, player)
                        && horse.position().equals(beforeDeniedChorus),
                "chorus respects datapack forbidden landing block tag");
        h.setBlock(5, 0, 5, Blocks.STONE);
        cfg.chorusFruitTeleport = false;
        chorus.apply(h.getLevel(), net.minecraft.world.item.ItemStack.EMPTY, player);
        check(h, player.getVehicle() == null && horse.position().equals(beforeDeniedChorus),
                "disabled chorus carry uses vanilla dismount without general recapture");
        cfg.chorusFruitTeleport = true;
        cfg.safetySearchRadius = 2;
        horse.snapTo(origin);
    }

    private static void safety(GameTestHelper h, Entity horse, Vec3 origin) {
        check(h, SafetyChecker.isSafe(horse, h.getLevel(), origin), "clear ground destination is safe");
        Vec3 fractional = origin.add(0, 0.37, 0);
        check(h, origin.equals(SafetyChecker.findSafePosition(horse, h.getLevel(), fractional, 2)),
                "search snaps fractional impact downward to nearest ground");
        check(h, SafetyChecker.findSafePosition(horse, h.getLevel(), fractional, 0) == null,
                "zero radius does not relocate an unsafe impact");
        check(h, origin.equals(SafetyChecker.findSafePosition(horse, h.getLevel(), origin.add(0, -0.2, 0), 2)),
                "search can move upward out of the impacted ground");
        check(h, SafetyChecker.findSafePosition(horse, h.getLevel(), origin.add(0, 2.37, 0), 2) == null,
                "search does not exceed the vertical radius to reach ground");
        h.setBlock(5, 1, 5, Blocks.STONE_SLAB);
        check(h, SafetyChecker.isSafe(horse, h.getLevel(), origin.add(0, 0.5, 0)), "slab top is safe using actual collision shape");
        check(h, origin.add(0, 0.5, 0).equals(SafetyChecker.findSafePosition(horse, h.getLevel(), fractional, 2)),
                "search discovers the actual slab surface");
        check(h, !SafetyChecker.isSafe(horse, h.getLevel(), origin), "intersecting slab is blocked");
        h.setBlock(5, 1, 5, Blocks.AIR);
        for (int y = 1; y <= 5; y++) h.setBlock(5, y, 5, Blocks.STONE);
        Vec3 besideWall = SafetyChecker.findSafePosition(horse, h.getLevel(), fractional, 2);
        check(h, besideWall != null && SafetyChecker.isSafe(horse, h.getLevel(), besideWall)
                        && besideWall.y == origin.y && besideWall.distanceToSqr(fractional) <= 8.0,
                "blocked pearl impact finds safe ground beside the wall within radius");
        for (int y = 1; y <= 5; y++) h.setBlock(5, y, 5, Blocks.AIR);
        var ghast = h.spawn(EntityTypes.HAPPY_GHAST, new Vec3(24, 7, 24));
        check(h, SafetyChecker.isSafe(ghast, h.getLevel(), ghast.position()), "flying mount does not require solid ground");
        ghast.discard();
        for (int x = 10; x <= 13; x++) for (int z = 10; z <= 13; z++) for (int y = 1; y <= 5; y++) h.setBlock(x, y, z, Blocks.WATER);
        var nautilus = h.spawn(EntityTypes.NAUTILUS, new Vec3(11.5, 2, 11.5));
        check(h, SafetyChecker.isSafe(nautilus, h.getLevel(), nautilus.position()), "nautilus can land in water");
        check(h, !SafetyChecker.isSafe(horse, h.getLevel(), nautilus.position()), "ordinary mount cannot land submerged");
        nautilus.discard();
        for (int x = 10; x <= 13; x++) for (int z = 10; z <= 13; z++) h.setBlock(x, 1, z, Blocks.LAVA);
        var strider = h.spawn(EntityTypes.STRIDER, new Vec3(11.5, 2, 11.5));
        // Clear water above the lava surface so only the feet contact lava.
        for (int x = 10; x <= 13; x++) for (int z = 10; z <= 13; z++) for (int y = 2; y <= 5; y++) h.setBlock(x, y, z, Blocks.AIR);
        check(h, SafetyChecker.isSafe(strider, h.getLevel(), strider.position()), "strider can land on a lava surface");
        check(h, !SafetyChecker.isSafe(strider, h.getLevel(), strider.position().add(0, -0.5, 0)), "strider rider must not be submerged in lava");
        strider.discard();
        var border = h.getLevel().getWorldBorder();
        check(h, !SafetyChecker.isWithinBounds(horse, h.getLevel(), new Vec3(border.getMaxX(), origin.y, origin.z)), "full mount footprint must fit world border");
        check(h, !SafetyChecker.isWithinBounds(horse, h.getLevel(), new Vec3(Double.NaN, origin.y, origin.z)), "nonfinite position rejected");
        for (int x = 17; x <= 27; x++) for (int y = 1; y <= 11; y++) for (int z = 17; z <= 27; z++) h.setBlock(x, y, z, Blocks.STONE);
        check(h, SafetyChecker.findSafePosition(horse, h.getLevel(), h.absoluteVec(new Vec3(22, 5, 22)), Integer.MAX_VALUE) == null,
                "fully blocked search with huge input terminates within bounded radius");
    }
}
