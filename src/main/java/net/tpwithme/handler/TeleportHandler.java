package net.tpwithme.handler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.equine.TraderLlama;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.TpWithMe;
import net.tpwithme.compat.OpenPartiesAndClaimsCompat;
import net.tpwithme.config.TpWithMeConfig;
import net.tpwithme.permission.PermissionManager;
import net.tpwithme.util.SafetyChecker;

import java.util.*;

/**
 * Central handler: captures the player's mount before a teleport and
 * re-teleports + remounts it afterwards.
 *
 * Confirmed package locations for 26.1:
 *   AbstractHorse / Llama / TraderLlama : net.minecraft.world.entity.animal.equine
 *   Camel / CamelHusk                  : net.minecraft.world.entity.animal.camel
 *   Pig                                : net.minecraft.world.entity.animal.pig
 *   Strider                            : net.minecraft.world.entity.monster
 *   AbstractNautilus (Nautilus /
 *     ZombieNautilus)                  : net.minecraft.world.entity.animal.nautilus
 */
public final class TeleportHandler {

    private TeleportHandler() {}

    // ─── Supported entity types ───────────────────────────────────────────────

    private static final Set<String> SUPPORTED_ENTITY_IDS = Set.of(
            "minecraft:horse",
            "minecraft:donkey",
            "minecraft:mule",
            "minecraft:skeleton_horse",
            "minecraft:zombie_horse",
            "minecraft:camel",
            "minecraft:camel_husk",
            "minecraft:pig",
            "minecraft:strider",
            "minecraft:nautilus",
            "minecraft:zombie_nautilus",
            "minecraft:llama",
            "minecraft:trader_llama",
            "minecraft:happy_ghast"
    );

    // ─── State ────────────────────────────────────────────────────────────────

    private static final Set<UUID> currentlyTeleporting =
            Collections.synchronizedSet(new HashSet<>());

    private static final Set<UUID> suppressedPlayers = new HashSet<>();

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Called before the player's teleport by the invocation-scoped wrapper.
     * Only checks eligibility (supported type, blacklist, saddle, permissions, cross-dim).
     * Safety and border checks are done in onPostTeleport AFTER the player
     * arrives, because destination chunks may not yet be loaded before teleporting.
     */
    public static Entity captureVehicle(ServerPlayer player, ServerLevel targetLevel) {
        return suppressedPlayers.contains(player.getUUID()) ? null : getEligibleVehicle(player, targetLevel);
    }

    private static ServerPlayer teleportWithoutMount(ServerPlayer player, TeleportTransition transition) {
        boolean added = suppressedPlayers.add(player.getUUID());
        try {
            return player.teleport(transition);
        } finally {
            if (added) suppressedPlayers.remove(player.getUUID());
        }
    }

    public static void clearState() {
        suppressedPlayers.clear();
        currentlyTeleporting.clear();
    }

    public static ServerPlayer handleMountedPearlTeleport(ServerPlayer player, TeleportTransition original) {
        if (!TpWithMeConfig.get().enderPearlTeleport) {
            return teleportWithoutMount(player, original);
        }

        if (!PermissionManager.canEnderPearlTeleport(player)) {
            return teleportWithoutMount(player, original);
        }

        Entity vehicle = getEligibleVehicle(player, original.newLevel());
        if (vehicle == null) {
            return teleportWithoutMount(player, original);
        }

        Vec3 mountPos = original.position();
        if (TpWithMeConfig.get().checkSafety) {
            int radius = TpWithMeConfig.get().safetySearchRadius;
            mountPos = SafetyChecker.findSafePosition(vehicle, original.newLevel(), original.position(), radius);
            if (mountPos == null) {
                player.sendSystemMessage(Component.literal(
                        "[TpWithMe] Not enough space at pearl landing for you and your mount."));
                return null;
            }
        }

        if (!canEnterDestination(player, vehicle, original.newLevel(), mountPos, OpenPartiesAndClaimsCompat.TeleportType.ENDER_PEARL)) {
            player.sendSystemMessage(Component.literal(
                    "[TpWithMe] Open Parties and Claims blocks mounted ender pearl entry into this claim."));
            return teleportWithoutMount(player, original);
        }

        Entity newVehicle = teleportVehicleRoot(player, vehicle, original.newLevel(), mountPos, OpenPartiesAndClaimsCompat.TeleportType.ENDER_PEARL);
        return newVehicle != null ? player : null;
    }

    public static Boolean handleMountedChorusFruitTeleport(ServerLevel level, ServerPlayer player, float diameter) {
        if (!TpWithMeConfig.get().chorusFruitTeleport) {
            return null;
        }

        if (!PermissionManager.canChorusFruitTeleport(player)) {
            return null;
        }

        Entity vehicle = getEligibleVehicle(player, level);
        if (vehicle == null) {
            return null;
        }

        Vec3 originalPos = player.position();
        boolean opacBlockedDestination = false;

        for (int attempt = 0; attempt < 16; attempt++) {
            double x = player.getX() + (player.getRandom().nextDouble() - 0.5D) * diameter;
            double y = Mth.clamp(
                    player.getY() + (player.getRandom().nextDouble() - 0.5D) * diameter,
                    level.getMinY(),
                    level.getMinY() + level.getLogicalHeight() - 1
            );
            double z = player.getZ() + (player.getRandom().nextDouble() - 0.5D) * diameter;

            Vec3 mountPos = findSafeChorusFruitDestination(vehicle, level, x, y, z);
            if (mountPos == null) {
                continue;
            }

            if (!canEnterDestination(player, vehicle, level, mountPos, OpenPartiesAndClaimsCompat.TeleportType.CHORUS_FRUIT)) {
                opacBlockedDestination = true;
                continue;
            }

            Entity newVehicle = teleportVehicleRoot(player, vehicle, level, mountPos, OpenPartiesAndClaimsCompat.TeleportType.CHORUS_FRUIT);
            if (newVehicle == null) {
                continue;
            }

            level.gameEvent(GameEvent.TELEPORT, originalPos, GameEvent.Context.of(player));
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS);
            player.resetFallDistance();
            player.resetCurrentImpulseContext();
            return true;
        }

        if (opacBlockedDestination) {
            player.sendSystemMessage(Component.literal(
                    "[TpWithMe] Open Parties and Claims blocks mounted chorus fruit entry into the available destinations."));
        }
        return false;
    }

    public static void onPostTeleport(ServerPlayer player, Entity vehicle, ServerLevel targetLevel, Vec3 targetPos) {
        if (!vehicle.isAlive() || vehicle.isRemoved() || !player.isAlive() || !TpWithMeConfig.get().enabled) return;
        boolean crossDimensional = vehicle.level() != targetLevel;

        // World border check
        WorldBorder border = targetLevel.getWorldBorder();
        if (!border.isWithinBounds(targetPos.x, targetPos.z)) {
            player.sendSystemMessage(Component.literal(
                    "[TpWithMe] Destination is outside the world border – your mount stayed behind."));
            return;
        }

        // Safety check – done HERE after the player arrived so chunks are loaded.
        // If the exact position is blocked, search nearby for a safe spot.
        Vec3 mountPos = targetPos;
        if (TpWithMeConfig.get().checkSafety) {
            int radius = TpWithMeConfig.get().safetySearchRadius;
            mountPos = SafetyChecker.findSafePosition(vehicle, targetLevel, targetPos, radius);
            if (mountPos == null) {
                player.sendSystemMessage(Component.literal(
                        "[TpWithMe] Not enough space at destination – your mount stayed behind."));
                return;
            }
            if (!mountPos.equals(targetPos)) {
                player.sendSystemMessage(Component.literal(
                        "[TpWithMe] Mount adjusted to a nearby safe position."));
            }
        }

        if (!canEnterDestination(player, vehicle, targetLevel, mountPos, OpenPartiesAndClaimsCompat.TeleportType.GENERAL)) {
            player.sendSystemMessage(Component.literal(
                    "[TpWithMe] Open Parties and Claims blocks your mount from entering this claim."));
            return;
        }

        UUID vehicleId = vehicle.getUUID();
        currentlyTeleporting.add(vehicleId);

        try {
            if (TpWithMeConfig.get().applyTeleportProtection) {
                applyProtection(vehicle, TpWithMeConfig.get().protectionDurationTicks);
            }

            TeleportTransition transition = new TeleportTransition(
                    targetLevel,
                    mountPos,
                    Vec3.ZERO,
                    player.getYRot(),
                    0.0f,
                    Set.of(),
                    TeleportTransition.DO_NOTHING
            );

            Entity newVehicle = vehicle.teleport(transition);

            if (newVehicle == null) {
                TpWithMe.LOGGER.warn("{} teleport() returned null for {}.",
                        TpWithMe.prefix(),
                        vehicle.getType().toShortString());
                return;
            }

            // startRiding(Entity, boolean force, boolean cancelEvent)
            boolean success = player.startRiding(newVehicle, true, false);
            if (!success) {
                TpWithMe.LOGGER.warn("{} Could not remount player on {}.",
                        TpWithMe.prefix(),
                        newVehicle.getType().toShortString());
            } else {
                TpWithMe.LOGGER.debug("{} Player {} remounted {}.",
                        TpWithMe.prefix(),
                        player.getName().getString(), newVehicle.getType().toShortString());
                // Schedule a check a few ticks later to catch post-teleport dismounts
                // (e.g. Minecraft forces a dismount on the next tick due to block collision).
                RemountWatcher.schedule(player, newVehicle, OpenPartiesAndClaimsCompat.TeleportType.GENERAL, crossDimensional);
            }

        } finally {
            currentlyTeleporting.remove(vehicleId);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static boolean isSupportedType(Entity entity) {
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && SUPPORTED_ENTITY_IDS.contains(id.toString());
    }

    private static Entity teleportVehicleRoot(ServerPlayer player, Entity vehicle, ServerLevel targetLevel, Vec3 mountPos,
                                             OpenPartiesAndClaimsCompat.TeleportType type) {
        RemountWatcher.cancel(player.getUUID());
        boolean crossDimensional = vehicle.level() != targetLevel;
        UUID vehicleId = vehicle.getUUID();
        currentlyTeleporting.add(vehicleId);

        try {
            if (TpWithMeConfig.get().applyTeleportProtection) {
                applyProtection(vehicle, TpWithMeConfig.get().protectionDurationTicks);
            }

            TeleportTransition vehicleTransition = new TeleportTransition(
                    targetLevel,
                    mountPos,
                    vehicle.getDeltaMovement(),
                    vehicle.getYRot(),
                    vehicle.getXRot(),
                    Set.of(),
                    TeleportTransition.DO_NOTHING
            );

            Entity newVehicle = vehicle.teleport(vehicleTransition);
            if (newVehicle == null) {
                TpWithMe.LOGGER.warn("{} Mounted teleport returned null for {}.",
                        TpWithMe.prefix(),
                        vehicle.getType().toShortString());
                return null;
            }

            if (player.getVehicle() == null || !player.getVehicle().getUUID().equals(newVehicle.getUUID())) {
                TpWithMe.LOGGER.warn("{} Mounted teleport left player {} dismounted from {}.",
                        TpWithMe.prefix(),
                        player.getName().getString(),
                        newVehicle.getType().toShortString());
            }

            RemountWatcher.schedule(player, newVehicle, type, crossDimensional);
            return newVehicle;
        } finally {
            currentlyTeleporting.remove(vehicleId);
        }
    }

    private static Vec3 findSafeChorusFruitDestination(Entity vehicle, ServerLevel level, double x, double y, double z) {
        BlockPos targetPos = BlockPos.containing(x, y, z);
        if (!level.hasChunkAt(targetPos)) {
            return null;
        }

        boolean foundGround = false;
        double adjustedY = y;
        BlockPos scanPos = targetPos;

        while (!foundGround && scanPos.getY() > level.getMinY()) {
            BlockPos below = scanPos.below();
            BlockState belowState = level.getBlockState(below);
            if (belowState.entityCanStandOn(level, below, vehicle)) {
                foundGround = true;
                adjustedY = below.getY() + 1.0D;
            } else {
                adjustedY -= 1.0D;
                scanPos = below;
            }
        }

        if (!foundGround) {
            return null;
        }

        Vec3 groundedPos = new Vec3(x, adjustedY, z);
        Vec3 mountPos = groundedPos;
        if (TpWithMeConfig.get().checkSafety) {
            mountPos = SafetyChecker.findSafePosition(vehicle, level, groundedPos, TpWithMeConfig.get().safetySearchRadius);
            if (mountPos == null) {
                return null;
            }
        }

        AABB vehicleBox = vehicle.getDimensions(Pose.STANDING).makeBoundingBox(mountPos);
        if (level.containsAnyLiquid(vehicleBox)) {
            return null;
        }

        return mountPos;
    }

    private static Entity getEligibleVehicle(ServerPlayer player, ServerLevel targetLevel) {
        if (!TpWithMeConfig.get().enabled) return null;

        Entity vehicle = player.getVehicle();
        if (vehicle == null) return null;
        if (currentlyTeleporting.contains(vehicle.getUUID())) return null;
        if (!isSupportedType(vehicle)) return null;

        if (isBlacklisted(vehicle)) {
            TpWithMe.LOGGER.debug("{} {} is blacklisted; skipping.",
                    TpWithMe.prefix(),
                    vehicle.getType().toShortString());
            return null;
        }

        if (TpWithMeConfig.get().requireSaddle && !hasSaddle(vehicle)) {
            TpWithMe.LOGGER.debug("{} {} has no saddle; skipping.",
                    TpWithMe.prefix(),
                    vehicle.getType().toShortString());
            return null;
        }

        if (!PermissionManager.canUse(player)) {
            TpWithMe.LOGGER.debug("{} {} lacks permission {}; skipping.",
                    TpWithMe.prefix(),
                    player.getName().getString(), PermissionManager.USE_PERMISSION);
            return null;
        }

        boolean crossDim = vehicle.level() != targetLevel;
        if (crossDim && !TpWithMeConfig.get().crossDimensionalTeleport) {
            player.sendSystemMessage(Component.literal(
                    "[TpWithMe] Cross-dimensional mount teleport is disabled in the config."));
            return null;
        }
        if (crossDim && !PermissionManager.canCrossDimensionalTeleport(player)) {
            player.sendSystemMessage(Component.literal(
                    "[TpWithMe] You don't have permission to take your mount across dimensions."));
            return null;
        }

        return vehicle;
    }

    public static boolean canRecoverMount(ServerPlayer player, Entity vehicle,
                                          OpenPartiesAndClaimsCompat.TeleportType type, boolean crossDimensional) {
        var config = TpWithMeConfig.get();
        if (!config.enabled || !PermissionManager.canUse(player) || !vehicle.isAlive() || vehicle.isRemoved()
                || !isSupportedType(vehicle) || isBlacklisted(vehicle)
                || (config.requireSaddle && !hasSaddle(vehicle))) return false;
        if (crossDimensional && (!config.crossDimensionalTeleport || !PermissionManager.canCrossDimensionalTeleport(player))) return false;
        if (type == OpenPartiesAndClaimsCompat.TeleportType.ENDER_PEARL
                && (!config.enderPearlTeleport || !PermissionManager.canEnderPearlTeleport(player))) return false;
        if (type == OpenPartiesAndClaimsCompat.TeleportType.CHORUS_FRUIT
                && (!config.chorusFruitTeleport || !PermissionManager.canChorusFruitTeleport(player))) return false;
        return canEnterDestination(player, vehicle, player.level(), vehicle.position(), type)
                && (!config.checkSafety || SafetyChecker.isSafe(vehicle, player.level(), vehicle.position()));
    }

    private static boolean canEnterDestination(
            ServerPlayer player,
            Entity vehicle,
            ServerLevel level,
            Vec3 pos,
            OpenPartiesAndClaimsCompat.TeleportType teleportType
    ) {
        if (!SafetyChecker.isWithinBounds(vehicle, level, pos)) return false;
        if (!TpWithMeConfig.get().respectOpenPartiesAndClaims) return true;
        AABB box = vehicle.getDimensions(Pose.STANDING).makeBoundingBox(pos);
        // A large mount can overlap claims even when its center is outside them.
        for (int x = Mth.floor(box.minX) >> 4; x <= (Mth.floor(Math.nextDown(box.maxX)) >> 4); x++) {
            for (int z = Mth.floor(box.minZ) >> 4; z <= (Mth.floor(Math.nextDown(box.maxZ)) >> 4); z++) {
                if (!OpenPartiesAndClaimsCompat.canTeleportInto(level, new BlockPos(x << 4, Mth.floor(pos.y), z << 4), player, teleportType)) return false;
            }
        }
        return true;
    }

    private static boolean isBlacklisted(Entity entity) {
        List<String> list = TpWithMeConfig.get().blacklistedEntities;
        if (list == null || list.isEmpty()) return false;
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && list.contains(id.toString());
    }

    /**
     * Saddle check using proper instanceof casts with confirmed 26.1 packages.
     *
     * - Llama / TraderLlama      : always true (lead-controlled, no saddle)
     * - AbstractHorse family
     *   (Horse, Donkey, Mule,
     *    SkeletonHorse, ZombieHorse,
     *    Camel, CamelHusk, Llama,
     *    TraderLlama)            : isSaddled()
     * - Pig                      : isSaddled()
     * - Strider                  : isSaddled()
     * - AbstractNautilus
     *   (Nautilus, ZombieNautilus): isSaddled()
     * - Happy Ghast              : EquipmentSlot.BODY not empty (harness)
     */
    private static boolean hasSaddle(Entity entity) {
        // Llamas are lead-controlled – no saddle needed
        if (entity instanceof Llama || entity instanceof TraderLlama) {
            return true;
        }

        // AbstractHorse covers: Horse, Donkey, Mule, SkeletonHorse, ZombieHorse
        // Camel and CamelHusk also extend AbstractHorse in 26.1
        if (entity instanceof AbstractHorse horse) {
            return horse.isSaddled();
        }

        if (entity instanceof Pig pig) {
            return pig.isSaddled();
        }

        if (entity instanceof Strider strider) {
            return strider.isSaddled();
        }

        // Nautilus and ZombieNautilus both extend AbstractNautilus
        if (entity instanceof AbstractNautilus nautilus) {
            return nautilus.isSaddled();
        }

        // Happy Ghast: harness occupies EquipmentSlot.BODY (no isSaddled(), check slot directly)
        if (entity instanceof HappyGhast ghast) {
            return !ghast.getItemBySlot(EquipmentSlot.BODY).isEmpty();
        }

        return true;
    }

    private static void applyProtection(Entity entity, int durationTicks) {
        if (entity instanceof LivingEntity living) {
            living.addEffect(new MobEffectInstance(
                    MobEffects.RESISTANCE,
                    durationTicks,
                    4,
                    false,
                    false
            ));
        }
    }
}
