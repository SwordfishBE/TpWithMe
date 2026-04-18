package net.tpwithme;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.tpwithme.config.TpWithMeConfig;

import java.util.ArrayList;

final class TpWithMeClothConfigScreen {
    private TpWithMeClothConfigScreen() {
    }

    static Screen create(Screen parent) {
        TpWithMeConfig config = TpWithMe.loadConfigForEditing();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("TpWithMe Config"))
                .setSavingRunnable(() -> TpWithMe.applyEditedConfig(config));

        ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));
        ConfigCategory safety = builder.getOrCreateCategory(Component.literal("Safety"));
        ConfigCategory entities = builder.getOrCreateCategory(Component.literal("Entities"));
        ConfigEntryBuilder entries = builder.entryBuilder();

        general.addEntry(entries.startTextDescription(Component.literal(
                "Edits here change this installation's local config. On multiplayer servers, the server config still decides TpWithMe behavior."
        )).build());

        general.addEntry(entries.startBooleanToggle(Component.literal("Enabled"), config.enabled)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Master switch for TpWithMe. Disable this to keep the mod loaded but inactive."))
                .setSaveConsumer(value -> config.enabled = value)
                .build());

        general.addEntry(entries.startBooleanToggle(Component.literal("Use LuckPerms"), config.useLuckPerms)
                .setDefaultValue(false)
                .setTooltip(Component.literal("Enable LuckPerms permission nodes when the luckperms mod is installed."))
                .setSaveConsumer(value -> config.useLuckPerms = value)
                .build());

        general.addEntry(entries.startBooleanToggle(Component.literal("Cross-Dimensional Teleport"), config.crossDimensionalTeleport)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Allow mounts to follow teleports between dimensions like the Overworld, Nether, and End."))
                .setSaveConsumer(value -> config.crossDimensionalTeleport = value)
                .build());

        general.addEntry(entries.startBooleanToggle(Component.literal("Ender Pearl Teleport"), config.enderPearlTeleport)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Allow ender pearls to take your mount along. Disable this for vanilla pearl behaviour."))
                .setSaveConsumer(value -> config.enderPearlTeleport = value)
                .build());

        general.addEntry(entries.startBooleanToggle(Component.literal("Chorus Fruit Teleport"), config.chorusFruitTeleport)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Allow chorus fruit to take your mount along. Disable this for vanilla chorus fruit behaviour."))
                .setSaveConsumer(value -> config.chorusFruitTeleport = value)
                .build());

        general.addEntry(entries.startBooleanToggle(Component.literal("Require Saddle"), config.requireSaddle)
                .setDefaultValue(true)
                .setTooltip(
                        Component.literal("Require the correct control item before a mount can teleport."),
                        Component.literal("Saddles apply to horses, camels, pigs, striders, and nautilus mounts."),
                        Component.literal("Llamas are exempt, and happy ghasts require a harness.")
                )
                .setSaveConsumer(value -> config.requireSaddle = value)
                .build());

        safety.addEntry(entries.startBooleanToggle(Component.literal("Check Safety"), config.checkSafety)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Check for enough room at the destination before moving the mount."))
                .setSaveConsumer(value -> config.checkSafety = value)
                .build());

        safety.addEntry(entries.startIntField(Component.literal("Safety Search Radius"), config.safetySearchRadius)
                .setDefaultValue(2)
                .setMin(0)
                .setTooltip(Component.literal("How many blocks around the destination to search for a safe fallback spot."))
                .setSaveConsumer(value -> config.safetySearchRadius = value)
                .build());

        safety.addEntry(entries.startBooleanToggle(Component.literal("Apply Teleport Protection"), config.applyTeleportProtection)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Apply Resistance V to the mount right after teleporting."))
                .setSaveConsumer(value -> config.applyTeleportProtection = value)
                .build());

        safety.addEntry(entries.startIntField(Component.literal("Protection Duration Ticks"), config.protectionDurationTicks)
                .setDefaultValue(60)
                .setMin(0)
                .setTooltip(Component.literal("Duration of the post-teleport protection. 20 ticks = 1 second."))
                .setSaveConsumer(value -> config.protectionDurationTicks = value)
                .build());

        entities.addEntry(entries.startStrList(Component.literal("Blacklisted Entities"), new ArrayList<>(config.blacklistedEntities))
                .setDefaultValue(new ArrayList<>())
                .setTooltip(
                        Component.literal("Entity type IDs that TpWithMe must never teleport."),
                        Component.literal("Use namespaced IDs like minecraft:horse or mymod:custom_mount.")
                )
                .setSaveConsumer(value -> config.blacklistedEntities = new ArrayList<>(value))
                .build());

        return builder.build();
    }
}
