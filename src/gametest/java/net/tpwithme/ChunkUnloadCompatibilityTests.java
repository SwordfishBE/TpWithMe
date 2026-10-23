package net.tpwithme;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.Vec3;

import static net.tpwithme.RegressionTests.*;

final class ChunkUnloadCompatibilityTests {
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void run(GameTestHelper h) {
        if (!FabricLoader.getInstance().isModLoaded("leashedteleport")) return;
        var cow = h.spawn(EntityTypes.COW, new Vec3(7.5, 1, 7.5));
        cow.setNoAi(true);
        var player = h.makeMockServerPlayerInLevel();
        player.snapTo(cow.position());
        try {
            var area = cow.getBoundingBox().inflate(4);
            int leadsBefore = leadCount(h.getLevel(), area);
            var pendingClass = Class.forName("net.leashedteleport.handler.PendingLeashTeleports");
            var typeClass = (Class<? extends Enum>) Class.forName("net.leashedteleport.compat.OpenPartiesAndClaimsCompat$TeleportType");
            var general = Enum.valueOf(typeClass, "GENERAL");
            var pendingField = pendingClass.getDeclaredField("pending");
            pendingField.setAccessible(true);
            var requests = (java.util.Map<?, ?>) pendingField.get(null);
            pendingClass.getMethod("schedule", ServerPlayer.class, Mob.class, typeClass).invoke(null, player, cow, general);
            try {
                EntityVisibilityTestSupport.setVisibility(h.getLevel(), cow.chunkPosition(), Visibility.HIDDEN);
                check(h, h.getLevel().getEntity(cow.getUUID()) == null && !cow.isRemoved(),
                        "hidden leashed mob remains loaded but absent from public UUID lookup");
                check(h, requests.containsKey(cow.getUUID()),
                        "visibility unload does not cancel pending leash transfer");
                pendingClass.getMethod("prepareTeleport", ServerPlayer.class, typeClass).invoke(null, player, general);
                pendingClass.getMethod("retargetPlayer", ServerPlayer.class).invoke(null, player);
                check(h, requests.containsKey(cow.getUUID()),
                        "rapid retarget preserves leash request in hidden source section");
                pendingClass.getMethod("tick", net.minecraft.server.MinecraftServer.class).invoke(null, h.getLevel().getServer());
                check(h, cow.getLeashHolder() == player && !requests.containsKey(cow.getUUID()),
                        "hidden loaded leashed mob completes pending transfer without refund");
                var handler = Class.forName("net.leashedteleport.handler.LeashTeleportHandler");
                var eligible = (java.util.List<?>) handler.getMethod("collectEligibleMobs", ServerPlayer.class).invoke(null, player);
                check(h, eligible.contains(cow), "same-tick history finds hidden recently teleported leashed mob");
            } finally {
                EntityVisibilityTestSupport.setVisibility(h.getLevel(), cow.chunkPosition(), Visibility.TICKING);
            }
            check(h, leadCount(h.getLevel(), area) == leadsBefore,
                    "visibility downgrade neither drops nor duplicates detached lead");
            pendingClass.getMethod("schedule", ServerPlayer.class, Mob.class, typeClass).invoke(null, player, cow, general);
            cow.discard();
            check(h, !requests.containsKey(cow.getUUID()) && leadCount(h.getLevel(), area) == leadsBefore,
                    "actual entity removal cancels leash request and defers refund");
            pendingClass.getMethod("tick", net.minecraft.server.MinecraftServer.class).invoke(null, h.getLevel().getServer());
            check(h, leadCount(h.getLevel(), area) == leadsBefore + 1,
                    "deferred unload cleanup returns exactly one lead");
            pendingClass.getMethod("tick", net.minecraft.server.MinecraftServer.class).invoke(null, h.getLevel().getServer());
            check(h, leadCount(h.getLevel(), area) == leadsBefore + 1,
                    "repeated cleanup does not duplicate returned lead");
            crossDimension(h, player);
        } catch (ReflectiveOperationException e) {
            TpWithMe.LOGGER.error("Chunk-unload compatibility reflection failed", e);
            throw new RuntimeException(e);
        } catch (RuntimeException e) {
            TpWithMe.LOGGER.error("Chunk-unload compatibility probe failed", e);
            throw e;
        } finally {
            cow.discard();
            h.getLevel().getServer().getPlayerList().remove(player);
        }
    }

    @SuppressWarnings("unchecked")
    private static void crossDimension(GameTestHelper h, ServerPlayer player) throws ReflectiveOperationException {
        var traveler = h.spawn(EntityTypes.COW, new Vec3(7.5, 1, 7.5));
        traveler.setNoAi(true);
        traveler.setNoGravity(true);
        player.snapTo(traveler.position());
        Vec3 origin = player.position();
        var id = traveler.getUUID();
        var nether = h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        Vec3 target = new Vec3(0.5, 80, 0.5);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                nether.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, 79, z),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                for (int y = 80; y <= 85; y++) nether.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, y, z),
                        net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
        }
        var lookup = Class.forName("net.leashedteleport.util.LoadedMobLookup")
                .getMethod("find", ServerLevel.class, java.util.UUID.class, Vec3.class);
        var targetChunk = new net.minecraft.world.level.ChunkPos(0, 0);
        Mob current = traveler;
        try {
            EntityVisibilityTestSupport.setVisibility(nether, targetChunk, Visibility.HIDDEN);
            traveler.setLeashedTo(player, true);
            player.teleport(new net.minecraft.world.level.portal.TeleportTransition(nether, target, Vec3.ZERO,
                    0, 0, java.util.Set.of(), net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            current = (Mob) lookup.invoke(null, nether, id, target);
            check(h, current != null && current.getLeashHolder() == player,
                    "cross-dimensional leash reattaches even before destination entity tracking");
            check(h, nether.getEntity(id) == null && !current.isRemoved(),
                    "cross-dimensional leash test actually lands in hidden loaded entity section");
            player.teleport(new net.minecraft.world.level.portal.TeleportTransition(h.getLevel(), origin, Vec3.ZERO,
                    0, 0, java.util.Set.of(), net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            current = (Mob) lookup.invoke(null, h.getLevel(), id, origin);
            check(h, current != null && current.getLeashHolder() == player,
                    "immediate return across dimensions retains leashed mob from hidden destination");
        } finally {
            EntityVisibilityTestSupport.setVisibility(nether, targetChunk, Visibility.TICKING);
            if (current != null) {
                current.removeLeash();
                current.discard();
            }
            traveler.discard();
        }
    }

    private static int leadCount(ServerLevel level, net.minecraft.world.phys.AABB area) {
        return level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area).stream()
                .filter(item -> item.getItem().is(net.minecraft.world.item.Items.LEAD))
                .mapToInt(item -> item.getItem().getCount()).sum();
    }
}
