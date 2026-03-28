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

    private static boolean luckPermsInstalled;
    private static boolean luckPermsActive;

    private PermissionManager() {}

    public static void refreshState() {
        luckPermsInstalled = FabricLoader.getInstance().isModLoaded("luckperms");
        luckPermsActive = TpWithMeConfig.get().useLuckPerms && luckPermsInstalled;

        if (luckPermsActive) {
            TpWithMe.LOGGER.info("[TpWithMe] LuckPerms permissions are active.");
            return;
        }

        if (TpWithMeConfig.get().useLuckPerms) {
            TpWithMe.LOGGER.warn("[TpWithMe] useLuckPerms is enabled, but LuckPerms is not installed. Everyone can use TpWithMe.");
            return;
        }

        TpWithMe.LOGGER.info("[TpWithMe] LuckPerms integration is disabled in the config.");
    }

    public static boolean isLuckPermsActive() {
        return luckPermsActive;
    }

    public static boolean canUse(ServerPlayer player) {
        return !luckPermsActive || Permissions.check(player, USE_PERMISSION, true);
    }

    public static boolean canCrossDimensionalTeleport(ServerPlayer player) {
        return !luckPermsActive || Permissions.check(player, CROSS_DIMENSIONAL_TELEPORT_PERMISSION, true);
    }
}
