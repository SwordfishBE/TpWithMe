package net.tpwithme.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.tpwithme.TpWithMe;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TpWithMeConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("tpwithme.json");

    private static TpWithMeConfig instance = new TpWithMeConfig();

    /** Master switch. */
    public boolean enabled = true;

    /**
     * Allow cross-dimensional teleports (Overworld ↔ Nether ↔ End).
     * When false, only same-dimension teleports carry the mount.
     */
    public boolean crossDimensionalTeleport = true;

    /**
     * Only teleport the mount when the player has the appropriate control item:
     *   Saddle  – Horse, Donkey, Mule, SkeletonHorse, ZombieHorse,
     *             Camel, CamelHusk, Pig, Strider, Nautilus, ZombieNautilus
     *   Harness – Happy Ghast
     *   Exempt  – Llama, TraderLlama (lead-controlled)
     */
    public boolean requireSaddle = true;

    /**
     * Verify there is enough free space at the destination before teleporting.
     * Checks both the mount's bounding box AND the rider's space above it.
     * Prevents suffocation on tall mounts like Camel and Camel Husk.
     * Default: true.
     */
    public boolean checkSafety = true;

    /** Apply brief Resistance V to the mount right after teleporting. */
    public boolean applyTeleportProtection = true;

    /** Duration (ticks) of the post-teleport damage resistance. Default: 60 = 3 s. */
    public int protectionDurationTicks = 60;

    /**
     * Entity type IDs that must NEVER be teleported.
     * Use namespaced IDs: "minecraft:horse", "mymod:custom_mount", etc.
     */
    public List<String> blacklistedEntities = new ArrayList<>();

    public static TpWithMeConfig get() {
        return instance;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                TpWithMeConfig loaded = GSON.fromJson(reader, TpWithMeConfig.class);
                if (loaded != null) instance = loaded;
            } catch (IOException e) {
                TpWithMe.LOGGER.error("[TpWithMe] Failed to read config, using defaults.", e);
                instance = new TpWithMeConfig();
            }
        }
        save();
        TpWithMe.LOGGER.info("[TpWithMe] Config loaded.");
    }

    public static void save() {
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
            GSON.toJson(instance, writer);
        } catch (IOException e) {
            TpWithMe.LOGGER.error("[TpWithMe] Failed to save config.", e);
        }
    }
}
