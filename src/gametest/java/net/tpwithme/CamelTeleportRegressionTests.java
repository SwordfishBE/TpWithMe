package net.tpwithme;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.config.TpWithMeConfig;
import net.tpwithme.handler.RemountWatcher;
import net.tpwithme.handler.TeleportHandler;

import java.util.Set;

import static net.tpwithme.RegressionTests.*;

final class CamelTeleportRegressionTests {
    static void run(GameTestHelper h, ServerPlayer player, Vec3 origin, Vec3 target) throws Exception {
        var camel = h.spawn(EntityTypes.CAMEL, new Vec3(7.5, 1, 7.5));
        camel.setNoAi(true);
        camel.setNoGravity(true);
        camel.standUpInstantly();
        var cfg = TpWithMeConfig.get();
        boolean oldSaddle = cfg.requireSaddle;
        int oldRadius = cfg.safetySearchRadius;
        boolean oldProtection = cfg.applyTeleportProtection;
        cfg.requireSaddle = true;
        cfg.safetySearchRadius = 4;
        cfg.applyTeleportProtection = true;
        try {
            camel.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
            check(h, camel.isSaddled(), "camel saddle fixture equips real saddle slot");
            mount(player, camel);
            camel.positionRider(player);
            check(h, TeleportHandler.captureVehicle(player, h.getLevel()) == camel,
                    "saddled solo camel eligible with requireSaddle enabled");
            player.teleportTo(h.getLevel(), target.x, target.y, target.z, Set.of(), 0, 0, true);
            check(h, player.getVehicle() == camel && camel.position().distanceToSqr(target) < 0.01,
                    "saddled solo camel follows command teleportTo with standard safety");
            camel.positionRider(player);
            double riderOffset = player.getY() - camel.getY();
            TpWithMe.LOGGER.info("CAMEL HOME DIAGNOSTIC: riderOffset={}, dimensions={}, saddled={}, passengers={}",
                    riderOffset, camel.getDimensions(net.minecraft.world.entity.Pose.STANDING), camel.isSaddled(), camel.getPassengers().size());
            Vec3 riderHome = h.absoluteVec(new Vec3(7.5, 1, 7.5)).add(0, riderOffset, 0);
            player.teleport(transition(player, riderHome));
            check(h, player.getVehicle() == camel,
                    "solo camel follows a home saved at actual mounted rider height");
            camel.sitDown();
            camel.positionRider(player);
            player.teleport(transition(player, target));
            check(h, player.getVehicle() == camel && camel.position().distanceToSqr(target) < 0.01,
                    "sitting saddled camel follows teleport");
            camel.setItemSlot(EquipmentSlot.SADDLE, ItemStack.EMPTY);
            check(h, TeleportHandler.captureVehicle(player, h.getLevel()) == null,
                    "unsaddled camel is explicitly rejected when saddle requirement is enabled");
            camel.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("essential_commands")) {
                camel.standUpInstantly();
                essentialHome(player, h.absoluteVec(new Vec3(7.5, 1, 7.5)));
                check(h, player.getVehicle() == camel,
                        "real Essential Commands home teleport carries saddled solo camel");
                if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("leashedteleport")) {
                    var cow = h.spawn(EntityTypes.COW, new Vec3(8.5, 1, 7.5));
                    cow.setNoAi(true);
                    cow.setNoGravity(true);
                    Vec3 cowOrigin = cow.position();
                    try {
                        cow.setLeashedTo(player, true);
                        essentialHome(player, target.add(10, 0, 0));
                        check(h, player.getVehicle() == camel && cow.getLeashHolder() == player
                                        && cow.position().distanceToSqr(cowOrigin) > 4
                                        && cow.position().distanceToSqr(player.position()) < 100,
                                "Essential Commands home carries camel and leashed cow with both mods active");
                        Vec3 homeA = h.absoluteVec(new Vec3(7.5, 1, 7.5));
                        for (int i = 0; i < 4; i++) {
                            essentialHome(player, homeA);
                            essentialHome(player, target.add(10, 0, 0));
                        }
                        check(h, player.getVehicle() == camel && cow.getLeashHolder() == player
                                        && cow.position().distanceToSqr(player.position()) < 100,
                                "rapid same-tick home B-A-B retains camel and leashed cow");
                        Vec3 coldB = new Vec3(-Math.abs(homeA.x) - 8192.3, 66, 337.3);
                        var coldBlock = net.minecraft.core.BlockPos.containing(coldB);
                        check(h, h.getLevel().getChunkSource().getChunkNow(coldBlock.getX() >> 4,
                                        coldBlock.getZ() >> 4) == null,
                                "rapid home scenario starts with unreadable destination terrain");
                        essentialHome(player, coldB);
                        for (int i = 0; i < 4; i++) {
                            essentialHome(player, homeA);
                            essentialHome(player, coldB);
                        }
                        var leashPending = Class.forName("net.leashedteleport.handler.PendingLeashTeleports");
                        var requests = (java.util.Map<?, ?>) field(leashPending, "pending").get(null);
                        check(h, requests.containsKey(cow.getUUID()) && cow.getLeashHolder() == null,
                                "rapid cold home B-A-B retains detached leash request");
                        for (int x = coldBlock.getX() - 6; x <= coldBlock.getX() + 6; x++) {
                            for (int z = coldBlock.getZ() - 6; z <= coldBlock.getZ() + 6; z++) {
                                h.getLevel().setBlockAndUpdate(new net.minecraft.core.BlockPos(x, 65, z),
                                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                                for (int y = 66; y <= 73; y++) h.getLevel().setBlockAndUpdate(
                                        new net.minecraft.core.BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                            }
                        }
                        net.tpwithme.handler.PendingMountTeleports.tick(h.getLevel().getServer());
                        leashPending.getMethod("tick", net.minecraft.server.MinecraftServer.class)
                                .invoke(null, h.getLevel().getServer());
                        check(h, player.getVehicle() == camel && camel.position().distanceToSqr(coldB) < 0.01,
                                "rapid cold home B-A-B delivers camel only to final B");
                        check(h, cow.getLeashHolder() == player && cow.position().distanceToSqr(player.position()) < 100
                                        && !requests.containsKey(cow.getUUID()),
                                "rapid cold home B-A-B delivers leashed cow only to final B");
                    } finally {
                        cow.removeLeash();
                        cow.discard();
                    }
                }
            }
        } finally {
            player.stopRiding();
            camel.discard();
            RemountWatcher.clear();
            cfg.requireSaddle = oldSaddle;
            cfg.safetySearchRadius = oldRadius;
            cfg.applyTeleportProtection = oldProtection;
            player.snapTo(origin);
        }
    }

    static void essentialHome(ServerPlayer player, Vec3 target) throws Exception {
        var locationClass = Class.forName("com.fibermc.essentialcommands.types.MinecraftLocation");
        Object location = locationClass.getConstructor(net.minecraft.resources.ResourceKey.class,
                double.class, double.class, double.class).newInstance(player.level().dimension(), target.x, target.y, target.z);
        var teleport = Class.forName("com.fibermc.essentialcommands.teleportation.PlayerTeleporter")
                .getDeclaredMethod("execTeleport", ServerPlayer.class, locationClass, net.minecraft.network.chat.MutableComponent.class);
        teleport.setAccessible(true);
        teleport.invoke(null, player, location, net.minecraft.network.chat.Component.literal("home: camel"));
    }
}
