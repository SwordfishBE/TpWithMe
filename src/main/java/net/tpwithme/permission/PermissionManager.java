package net.tpwithme.permission;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.tpwithme.TpWithMe;
import net.tpwithme.config.TpWithMeConfig;

public final class PermissionManager {

    public static final String USE_PERMISSION = "tpwithme.use";
    public static final String CROSS_DIMENSIONAL_TELEPORT_PERMISSION =
            "tpwithme.crossdimensionalteleport";
    public static final String ENDER_PEARL_TELEPORT_PERMISSION =
            "tpwithme.enderpearlteleport";
    public static final String CHORUS_FRUIT_TELEPORT_PERMISSION =
            "tpwithme.chorusfruitteleport";

    private static boolean luckPermsInstalled;
    private static boolean luckPermsActive;

    private PermissionManager() {}

    public static void refreshState() {
        luckPermsInstalled = FabricLoader.getInstance().isModLoaded("luckperms");
        luckPermsActive = TpWithMeConfig.get().useLuckPerms && luckPermsInstalled;

        if (luckPermsActive) {
            TpWithMe.LOGGER.debug("{} LuckPerms permissions are active.", TpWithMe.prefix());
            return;
        }

        if (TpWithMeConfig.get().useLuckPerms) {
            TpWithMe.LOGGER.warn("{} useLuckPerms is enabled, but LuckPerms is not installed. Everyone can use TpWithMe.",
                    TpWithMe.prefix());
            return;
        }

        TpWithMe.LOGGER.debug("{} LuckPerms integration is disabled in the config.", TpWithMe.prefix());
    }

    public static boolean isLuckPermsActive() {
        return luckPermsActive;
    }

    public static boolean canUse(ServerPlayer player) {
        return !luckPermsActive || Permissions.check(player, USE_PERMISSION, false);
    }

    public static boolean canCrossDimensionalTeleport(ServerPlayer player) {
        return !luckPermsActive || Permissions.check(player, CROSS_DIMENSIONAL_TELEPORT_PERMISSION, false);
    }

    public static boolean canEnderPearlTeleport(ServerPlayer player) {
        return !luckPermsActive || Permissions.check(player, ENDER_PEARL_TELEPORT_PERMISSION, false);
    }

    public static boolean canChorusFruitTeleport(ServerPlayer player) {
        return !luckPermsActive || Permissions.check(player, CHORUS_FRUIT_TELEPORT_PERMISSION, false);
    }
}
