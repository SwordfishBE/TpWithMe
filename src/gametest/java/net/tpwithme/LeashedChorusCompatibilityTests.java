package net.tpwithme;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.config.TpWithMeConfig;
import net.tpwithme.handler.RemountWatcher;

import java.util.Map;

import static net.tpwithme.RegressionTests.*;

final class LeashedChorusCompatibilityTests {
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void run(GameTestHelper h) throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("leashedteleport")) return;
        var pendingClass = Class.forName("net.leashedteleport.handler.PendingLeashTeleports");
        var handler = Class.forName("net.leashedteleport.handler.LeashTeleportHandler");
        var configClass = Class.forName("net.leashedteleport.config.LeashedTeleportConfig");
        var permissionClass = Class.forName("net.leashedteleport.permission.PermissionManager");
        var typeClass = (Class<? extends Enum>) Class.forName("net.leashedteleport.compat.OpenPartiesAndClaimsCompat$TeleportType");
        var general = Enum.valueOf(typeClass, "GENERAL");
        var requests = (Map<?, ?>) field(pendingClass, "pending").get(null);
        var captures = (Map<?, ?>) field(handler, "PENDING_TELEPORTS").get(null);
        Object oldConfig = configClass.getMethod("get").invoke(null);
        Object config = configClass.getConstructor().newInstance();
        field(configClass, "instance").set(null, config);
        boolean oldMountChorus = TpWithMeConfig.get().chorusFruitTeleport;
        var player = h.makeMockServerPlayerInLevel();
        var cow = h.spawn(EntityTypes.COW, new Vec3(5.5, 1, 7.5));
        var horse = h.spawn(EntityTypes.HORSE, new Vec3(5.5, 1, 5.5));
        cow.setNoAi(true);
        cow.setNoGravity(true);
        horse.setNoAi(true);
        horse.setNoGravity(true);
        Vec3 source = cow.position();
        Vec3 target = h.absoluteVec(new Vec3(15.5, 1, 5.5));
        var schedule = pendingClass.getMethod("schedule", ServerPlayer.class, Mob.class, typeClass);
        var cancel = pendingClass.getMethod("cancelPlayer", MinecraftServer.class, java.util.UUID.class);
        try {
            for (int mode = 0; mode < 3; mode++) {
                configClass.getField("chorus_fruit_teleport").setBoolean(config, mode != 0);
                permissionDeniedPlayer = mode == 1 ? player.getUUID() : null;
                permissionDeniedNode = mode == 1 ? "leashedteleport.chorusfruitteleport" : null;
                field(permissionClass, "luckPermsActive").setBoolean(null, mode == 1);
                player.snapTo(h.absoluteVec(new Vec3(22.5, 5, 22.5)));
                cow.snapTo(source);
                cow.setLeashedTo(player, true);
                schedule.invoke(null, player, cow, general);
                Object request = requests.get(cow.getUUID());
                Vec3 before = player.position();
                check(h, !chorus(h, player, 0) && player.position().equals(before), "failed leash chorus leaves player unmoved (mode=" + mode + ")");
                check(h, requests.get(cow.getUUID()) == request && captures.isEmpty(),
                        "failed leash chorus preserves original request and releases synchronous capture");
                cancel.invoke(null, h.getLevel().getServer(), player.getUUID());
                player.snapTo(target);
                cow.removeLeash();
                cow.snapTo(source);
                schedule.invoke(null, player, cow, general);
                check(h, chorus(h, player, 0), "successful vanilla chorus runs with detached leash wait");
                check(h, !requests.containsKey(cow.getUUID()) && captures.isEmpty(),
                        "successful chorus cancels prior leash wait under every carry policy");
                pendingClass.getMethod("tick", MinecraftServer.class).invoke(null, h.getLevel().getServer());
                check(h, cow.position().equals(source), "old general leash transfer cannot replay after chorus");
                cow.removeLeash();
            }
            permissionDeniedPlayer = null;
            permissionDeniedNode = null;
            field(permissionClass, "luckPermsActive").setBoolean(null, false);
            configClass.getField("chorus_fruit_teleport").setBoolean(config, true);
            cow.snapTo(source);
            player.snapTo(source);
            cow.setLeashedTo(player, true);
            check(h, chorus(h, player, 0) && cow.getLeashHolder() == player
                            && cow.position().distanceToSqr(player.position()) < 0.01,
                    "allowed vanilla chorus carries attached leashed mob");

            cow.removeLeash();
            cow.snapTo(source);
            horse.snapTo(h.absoluteVec(new Vec3(5.5, 1, 5.5)));
            mount(player, horse);
            cow.setLeashedTo(player, true);
            TpWithMeConfig.get().chorusFruitTeleport = true;
            check(h, chorus(h, player, 0), "combined mounted chorus succeeds");
            check(h, player.getVehicle() == horse && cow.getLeashHolder() == player
                            && cow.position().distanceToSqr(horse.position()) < 0.01,
                    "TpWithMe custom chorus carries both mount and separate leashed mob");
            check(h, captures.isEmpty() && requests.isEmpty(), "combined chorus leaves no stale leash capture or terrain request");
        } finally {
            permissionDeniedPlayer = null;
            permissionDeniedNode = null;
            field(configClass, "instance").set(null, oldConfig);
            permissionClass.getMethod("refreshState").invoke(null);
            TpWithMeConfig.get().chorusFruitTeleport = oldMountChorus;
            cancel.invoke(null, h.getLevel().getServer(), player.getUUID());
            handler.getMethod("clearPlayer", java.util.UUID.class).invoke(null, player.getUUID());
            RemountWatcher.cancel(player.getUUID());
            player.stopRiding();
            cow.removeLeash();
            cow.discard();
            horse.discard();
            h.getLevel().getServer().getPlayerList().remove(player);
        }
    }

    private static boolean chorus(GameTestHelper h, ServerPlayer player, float diameter) {
        return new TeleportRandomlyConsumeEffect(diameter, false)
                .apply(h.getLevel(), new ItemStack(Items.CHORUS_FRUIT), player);
    }
}
