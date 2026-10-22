package net.tpwithme;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
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
        try {
            var area = cow.getBoundingBox().inflate(4);
            int leadsBefore = leadCount(h.getLevel(), area);
            var pendingClass = Class.forName("net.leashedteleport.handler.PendingLeashTeleports");
            var typeClass = (Class<? extends Enum>) Class.forName("net.leashedteleport.compat.OpenPartiesAndClaimsCompat$TeleportType");
            var general = Enum.valueOf(typeClass, "GENERAL");
            pendingClass.getMethod("schedule", ServerPlayer.class, Mob.class, typeClass).invoke(null, player, cow, general);
            var managerField = ServerLevel.class.getDeclaredField("entityManager");
            managerField.setAccessible(true);
            var manager = (PersistentEntitySectionManager<Entity>) managerField.get(h.getLevel());
            try {
                manager.updateChunkStatus(cow.chunkPosition(), Visibility.HIDDEN);
                check(h, true, "leash cancellation during chunk downgrade does not mutate active entity iteration");
            } finally {
                manager.updateChunkStatus(cow.chunkPosition(), Visibility.TICKING);
            }
            check(h, leadCount(h.getLevel(), area) == leadsBefore,
                    "unload callback defers lead drop instead of spawning during section iteration");
            pendingClass.getMethod("tick", net.minecraft.server.MinecraftServer.class).invoke(null, h.getLevel().getServer());
            check(h, leadCount(h.getLevel(), area) == leadsBefore + 1,
                    "deferred unload cleanup returns exactly one lead");
            pendingClass.getMethod("tick", net.minecraft.server.MinecraftServer.class).invoke(null, h.getLevel().getServer());
            check(h, leadCount(h.getLevel(), area) == leadsBefore + 1,
                    "repeated cleanup does not duplicate returned lead");
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

    private static int leadCount(ServerLevel level, net.minecraft.world.phys.AABB area) {
        return level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area).stream()
                .filter(item -> item.getItem().is(net.minecraft.world.item.Items.LEAD))
                .mapToInt(item -> item.getItem().getCount()).sum();
    }
}
