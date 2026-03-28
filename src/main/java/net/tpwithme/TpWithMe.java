package net.tpwithme;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.tpwithme.command.TpWithMeCommand;
import net.tpwithme.config.TpWithMeConfig;
import net.tpwithme.handler.RemountWatcher;
import net.tpwithme.permission.PermissionManager;
import net.tpwithme.util.ModrinthUpdateChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TpWithMe implements ModInitializer {

    public static final String MOD_ID = "tpwithme";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        TpWithMeConfig.load();
        PermissionManager.refreshState();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                TpWithMeCommand.register(dispatcher));

        RemountWatcher.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> ModrinthUpdateChecker.checkOnceAsync());

        LOGGER.info("[TpWithMe] Initialized – your mount will follow you anywhere!");
    }
}
