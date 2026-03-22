package net.tpwithme.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.tpwithme.TpWithMe;
import net.tpwithme.config.TpWithMeConfig;

/**
 * Registers the /tpwithme command:
 *   /tpwithme info    – Show current config values (everyone).
 *   /tpwithme reload  – Reload config from disk (operator level 2 / gamemaster).
 */
public final class TpWithMeCommand {

    private TpWithMeCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("tpwithme")
                        .then(Commands.literal("info")
                                .executes(TpWithMeCommand::executeInfo))
                        .then(Commands.literal("reload")
                                // 1.21.11: permission system uses PermissionCheck objects.
                                // Commands.hasPermission() returns a Predicate<CommandSourceStack>.
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(TpWithMeCommand::executeReload))
        );
    }

    private static int executeInfo(CommandContext<CommandSourceStack> ctx) {
        TpWithMeConfig cfg = TpWithMeConfig.get();
        String blacklist = (cfg.blacklistedEntities == null || cfg.blacklistedEntities.isEmpty())
                ? "(none)"
                : String.join(", ", cfg.blacklistedEntities);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "\n§6§lTpWithMe§r\n" +
                "§7enabled§r:                  §e" + cfg.enabled + "\n" +
                "§7crossDimensionalTeleport§r:  §e" + cfg.crossDimensionalTeleport + "\n" +
                "§7requireSaddle§r:             §e" + cfg.requireSaddle + "\n" +
                "§7checkSafety§r:               §e" + cfg.checkSafety + "\n" +
                "§7applyTeleportProtection§r:   §e" + cfg.applyTeleportProtection + "\n" +
                "§7protectionDurationTicks§r:   §e" + cfg.protectionDurationTicks + "\n" +
                "§7blacklistedEntities§r:       §e" + blacklist
        ), false);
        return 1;
    }

    private static int executeReload(CommandContext<CommandSourceStack> ctx) {
        TpWithMeConfig.load();
        ctx.getSource().sendSuccess(() ->
                Component.literal("§a[TpWithMe] Config reloaded successfully."), true);
        TpWithMe.LOGGER.info("[TpWithMe] Config reloaded by {}.",
                ctx.getSource().getTextName());
        return 1;
    }
}
