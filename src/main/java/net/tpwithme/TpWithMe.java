package net.tpwithme;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
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
    public static final ModMetadata MOD_METADATA = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .orElseThrow(() -> new IllegalStateException("Missing mod container for " + MOD_ID))
            .getMetadata();
    public static final String MOD_NAME = MOD_METADATA.getName();
    public static final String MOD_VERSION = MOD_METADATA.getVersion().getFriendlyString();

    @Override
    public void onInitialize() {
        TpWithMeConfig.load();
        PermissionManager.refreshState();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                TpWithMeCommand.register(dispatcher));

        RemountWatcher.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> ModrinthUpdateChecker.checkOnceAsync());

        LOGGER.info("[{}] Mod initialized. Version: {}", MOD_NAME, MOD_VERSION);
    }

    public static TpWithMeConfig loadConfigForEditing() {
        return TpWithMeConfig.loadForEditing();
    }

    public static void applyEditedConfig(TpWithMeConfig editedConfig) {
        TpWithMeConfig.applyEditedConfig(editedConfig);
        PermissionManager.refreshState();
    }

    public static String prefix() {
        return "[" + MOD_NAME + "]";
    }
}
