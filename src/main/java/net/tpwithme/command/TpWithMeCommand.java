package net.tpwithme.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.tpwithme.TpWithMe;
import net.tpwithme.config.TpWithMeConfig;
import net.tpwithme.permission.PermissionManager;

/**
 * Registers the /tpwithme command:
 *   /tpwithme info    – Show current config values (operator level 2 / gamemaster).
 *   /tpwithme reload  – Reload config from disk (operator level 2 / gamemaster).
 */
public final class TpWithMeCommand {

    private TpWithMeCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("tpwithme")
                        .then(Commands.literal("info")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(TpWithMeCommand::executeInfo))
                        .then(Commands.literal("reload")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(TpWithMeCommand::executeReload))
        );
    }

    private static int executeInfo(CommandContext<CommandSourceStack> ctx) {
        TpWithMeConfig cfg = TpWithMeConfig.get();
        String blacklist = (cfg.blacklistedEntities == null || cfg.blacklistedEntities.isEmpty())
                ? "(none)"
                : String.join(", ", cfg.blacklistedEntities);
        String luckPermsStatus = PermissionManager.isLuckPermsActive()
                ? "active"
                : (cfg.useLuckPerms ? "configured, but mod not installed" : "disabled");

        ctx.getSource().sendSuccess(() -> Component.literal(
                "\n§6§lTpWithMe§r\n" +
                "§7enabled§r:                  §e" + cfg.enabled + "\n" +
                "§7luckPerms§r:                §e" + luckPermsStatus + "\n" +
                "§7useLuckPerms§r:             §e" + cfg.useLuckPerms + "\n" +
                "§7crossDimensionalTeleport§r:  §e" + cfg.crossDimensionalTeleport + "\n" +
                "§7enderPearlTeleport§r:        §e" + cfg.enderPearlTeleport + "\n" +
                "§7chorusFruitTeleport§r:       §e" + cfg.chorusFruitTeleport + "\n" +
                "§7respectOpenPartiesAndClaims§r: §e" + cfg.respectOpenPartiesAndClaims + "\n" +
                "§7requireSaddle§r:             §e" + cfg.requireSaddle + "\n" +
                "§7checkSafety§r:               §e" + cfg.checkSafety + "\n" +
                "§7applyTeleportProtection§r:   §e" + cfg.applyTeleportProtection + "\n" +
                "§7protectionDurationTicks§r:   §e" + cfg.protectionDurationTicks + "\n" +
                "§7safetySearchRadius§r:        §e" + cfg.safetySearchRadius + "\n" +
                "§7blacklistedEntities§r:       §e" + blacklist
        ), false);
        return 1;
    }

    private static int executeReload(CommandContext<CommandSourceStack> ctx) {
        TpWithMeConfig.load();
        PermissionManager.refreshState();
        ctx.getSource().sendSuccess(() ->
                Component.literal("§a[TpWithMe] Config reloaded successfully."), true);
        TpWithMe.LOGGER.info("{} Config reloaded via command.", TpWithMe.prefix());
        return 1;
    }
}
