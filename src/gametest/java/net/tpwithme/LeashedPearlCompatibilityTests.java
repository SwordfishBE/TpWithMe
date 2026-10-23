package net.tpwithme;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.handler.RemountWatcher;
import net.tpwithme.config.TpWithMeConfig;

import java.util.Map;

import static net.tpwithme.RegressionTests.*;

final class LeashedPearlCompatibilityTests {
    static void run(GameTestHelper h) throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("leashedteleport")) return;
        var handler = Class.forName("net.leashedteleport.handler.LeashTeleportHandler");
        var configClass = Class.forName("net.leashedteleport.config.LeashedTeleportConfig");
        var permissions = Class.forName("net.leashedteleport.permission.PermissionManager");
        Object oldConfig = configClass.getMethod("get").invoke(null);
        Object config = configClass.getConstructor().newInstance();
        field(configClass, "instance").set(null, config);
        boolean oldPearl = TpWithMeConfig.get().enderPearlTeleport;
        var player = h.makeMockServerPlayerInLevel();
        var horse = h.spawn(EntityTypes.HORSE, new Vec3(5.5, 1, 5.5));
        var cow = h.spawn(EntityTypes.COW, new Vec3(5.5, 1, 7.5));
        horse.setNoAi(true);
        horse.setNoGravity(true);
        horse.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
        cow.setNoAi(true);
        cow.setNoGravity(true);
        Vec3 target = h.absoluteVec(new Vec3(15.5, 1, 5.5));
        Vec3 horseSource = horse.position(), cowSource = cow.position();
        try {
            check(h, player.connection.isAcceptingMessages(), "pearl fixture has an accepting player connection");
            for (int mode = 0; mode < 6; mode++) {
                reset(player, horse, cow, horseSource, cowSource);
                TpWithMeConfig.get().enderPearlTeleport = mode != 3;
                configClass.getField("ender_pearl_teleport").setBoolean(config, mode != 1);
                permissionDeniedPlayer = mode == 2 ? player.getUUID() : null;
                permissionDeniedNode = mode == 2 ? "leashedteleport.enderpearlteleport" : null;
                field(permissions, "luckPermsActive").setBoolean(null, mode == 2);
                if (mode != 4) mount(player, horse);
                cow.setLeashedTo(player, true);
                Vec3 landing = mode == 5 ? h.absoluteVec(new Vec3(22.5, 5, 22.5)) : target;
                impact(h, player, landing);
                boolean carriedMount = mode != 3 && mode != 4 && mode != 5;
                check(h, carriedMount
                                ? player.getVehicle() == horse && horse.position().distanceToSqr(target) < 0.01
                                : horse.position().equals(horseSource) && (mode == 5 ? player.getVehicle() == horse : player.getVehicle() == null),
                        "actual pearl preserves mount policy (allowed/disabled leash/denied leash/disabled mount/unmounted/blocked mode=" + mode + ")");
                boolean carriedLeash = mode == 0 || mode == 3 || mode == 4;
                check(h, cow.getLeashHolder() == player
                                && (carriedLeash ? cow.position().distanceToSqr(target) < 0.01 : cow.position().equals(cowSource)),
                        "actual pearl preserves separate leashed mob policy (mode=" + mode + ")");
                check(h, ((Map<?, ?>) field(handler, "PENDING_TELEPORTS").get(null)).isEmpty(),
                        "pearl success or rejection releases synchronous leash capture");
            }
            permissionDeniedPlayer = null;
            permissionDeniedNode = null;
            field(permissions, "luckPermsActive").setBoolean(null, false);
            configClass.getField("ender_pearl_teleport").setBoolean(config, true);
            TpWithMeConfig.get().enderPearlTeleport = true;
            if (FabricLoader.getInstance().isModLoaded("openpartiesandclaims")) {
                LeashedPearlOpacTests.run(h, player, horse, cow, horseSource, cowSource, target);
            }
        } finally {
            permissionDeniedPlayer = null;
            permissionDeniedNode = null;
            field(configClass, "instance").set(null, oldConfig);
            permissions.getMethod("refreshState").invoke(null);
            TpWithMeConfig.get().enderPearlTeleport = oldPearl;
            handler.getMethod("clearPlayer", java.util.UUID.class).invoke(null, player.getUUID());
            RemountWatcher.cancel(player.getUUID());
            player.stopRiding();
            cow.removeLeash();
            cow.discard();
            horse.discard();
            h.getLevel().getServer().getPlayerList().remove(player);
        }
    }

    static void reset(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.entity.Entity horse,
                      net.minecraft.world.entity.Mob cow, Vec3 horseSource, Vec3 cowSource) {
        RemountWatcher.cancel(player.getUUID());
        player.stopRiding();
        player.setHealth(player.getMaxHealth());
        horse.snapTo(horseSource);
        cow.removeLeash();
        cow.snapTo(cowSource);
        player.snapTo(horseSource);
    }

    static void impact(GameTestHelper h, net.minecraft.server.level.ServerPlayer player, Vec3 target) throws Exception {
        var pearl = new ThrownEnderpearl(h.getLevel(), player, new ItemStack(Items.ENDER_PEARL));
        try {
            pearl.snapTo(target);
            pearl.setOldPosAndRot();
            h.getLevel().addFreshEntity(pearl);
            var hit = ThrownEnderpearl.class.getDeclaredMethod("onHit", HitResult.class);
            hit.setAccessible(true);
            hit.invoke(pearl, new BlockHitResult(target, Direction.UP, BlockPos.containing(target).below(), false));
        } finally {
            pearl.discard();
        }
    }
}
