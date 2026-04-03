package net.tpwithme.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.tpwithme.TpWithMe;

import java.io.IOException;
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
     * Enable LuckPerms-backed permission checks when the mod is installed.
     * If false, TpWithMe ignores LuckPerms entirely.
     */
    public boolean useLuckPerms = false;

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
     * When checkSafety is true and the exact destination is blocked, search
     * nearby positions within this radius (in blocks) for a safe spot.
     * 0 = exact position only (old behaviour).
     * Default: 2.
     */
    public int safetySearchRadius = 2;

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
            try {
                String rawConfig = Files.readString(CONFIG_PATH);
                String json = stripJsonComments(rawConfig);
                TpWithMeConfig loaded = GSON.fromJson(json, TpWithMeConfig.class);
                if (loaded != null) instance = loaded;
                if (instance.blacklistedEntities == null) instance.blacklistedEntities = new ArrayList<>();
            } catch (IOException e) {
                TpWithMe.LOGGER.error("[TpWithMe] Failed to read config, using defaults.", e);
                instance = new TpWithMeConfig();
            } catch (Exception e) {
                TpWithMe.LOGGER.error("[TpWithMe] Failed to parse config, using defaults.", e);
                instance = new TpWithMeConfig();
            }
        }
        save();
        TpWithMe.LOGGER.info("[TpWithMe] Config loaded.");
    }

    public static void save() {
        try {
            Files.writeString(CONFIG_PATH, toCommentedJson(instance));
        } catch (IOException e) {
            TpWithMe.LOGGER.error("[TpWithMe] Failed to save config.", e);
        }
    }

    private static String toCommentedJson(TpWithMeConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        appendComment(sb, "Master switch. Set to false to disable the mod without removing it.");
        appendProperty(sb, "enabled", config.enabled, true);

        appendComment(sb, "Enable LuckPerms permission checks when the luckperms mod is installed.");
        appendComment(sb, "If false, TpWithMe ignores LuckPerms and allows everyone to use the mod.");
        appendProperty(sb, "useLuckPerms", config.useLuckPerms, true);

        appendComment(sb, "Allow mounts to follow through dimension changes like Overworld, Nether, and End.");
        appendComment(sb, "If false, only same-dimension teleports carry the mount.");
        appendProperty(sb, "crossDimensionalTeleport", config.crossDimensionalTeleport, true);

        appendComment(sb, "Require the correct control item before a mount can teleport.");
        appendComment(sb, "Saddle: Horse, Donkey, Mule, Skeleton Horse, Zombie Horse, Camel, Camel Husk, Pig, Strider, Nautilus, Zombie Nautilus.");
        appendComment(sb, "Harness: Happy Ghast. Exempt: Llama and Trader Llama.");
        appendProperty(sb, "requireSaddle", config.requireSaddle, true);

        appendComment(sb, "Check that there is enough room at the destination for both the mount and rider.");
        appendComment(sb, "If the exact spot is blocked, the mod can search nearby positions using safetySearchRadius.");
        appendProperty(sb, "checkSafety", config.checkSafety, true);

        appendComment(sb, "Apply Resistance V to the mount immediately after teleporting to prevent transition damage.");
        appendProperty(sb, "applyTeleportProtection", config.applyTeleportProtection, true);

        appendComment(sb, "Duration of the post-teleport protection in ticks. 20 ticks = 1 second.");
        appendProperty(sb, "protectionDurationTicks", config.protectionDurationTicks, true);

        appendComment(sb, "Search radius in blocks for a nearby safe position when the exact destination is blocked.");
        appendComment(sb, "Set to 0 to disable nearby searching and only test the exact destination.");
        appendProperty(sb, "safetySearchRadius", config.safetySearchRadius, true);

        appendComment(sb, "Entity type IDs that must never teleport, even if every other rule allows it.");
        appendComment(sb, "Use namespaced IDs like minecraft:horse or mymod:custom_mount.");
        appendStringListProperty(sb, "blacklistedEntities", config.blacklistedEntities);
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendComment(StringBuilder sb, String comment) {
        sb.append("  // ").append(comment).append('\n');
    }

    private static void appendProperty(StringBuilder sb, String key, boolean value, boolean trailingComma) {
        sb.append("  \"").append(key).append("\": ").append(value);
        if (trailingComma) sb.append(',');
        sb.append('\n').append('\n');
    }

    private static void appendProperty(StringBuilder sb, String key, int value, boolean trailingComma) {
        sb.append("  \"").append(key).append("\": ").append(value);
        if (trailingComma) sb.append(',');
        sb.append('\n').append('\n');
    }

    private static void appendStringListProperty(StringBuilder sb, String key, List<String> values) {
        sb.append("  \"").append(key).append("\": [");
        if (values != null && !values.isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < values.size(); i++) {
                sb.append("    ").append(GSON.toJson(values.get(i)));
                if (i < values.size() - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append("  ]\n");
        } else {
            sb.append("]\n");
        }
    }

    private static String stripJsonComments(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        boolean inString = false;
        boolean escaping = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            char next = i + 1 < input.length() ? input.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (current == '\n' || current == '\r') {
                    inLineComment = false;
                    sb.append(current);
                }
                continue;
            }

            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }

            if (inString) {
                sb.append(current);
                if (escaping) {
                    escaping = false;
                } else if (current == '\\') {
                    escaping = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
                sb.append(current);
                continue;
            }

            if (current == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }

            if (current == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }

            sb.append(current);
        }

        return sb.toString();
    }
}
