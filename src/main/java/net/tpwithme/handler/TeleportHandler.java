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

    private static final Set<EntityType<?>> SUPPORTED_TYPES = new HashSet<>(Arrays.asList(
            EntityType.HORSE,
            EntityType.DONKEY,
            EntityType.MULE,
            EntityType.SKELETON_HORSE,
            EntityType.ZOMBIE_HORSE,
            EntityType.CAMEL,
            EntityType.CAMEL_HUSK,
            EntityType.PIG,
            EntityType.STRIDER,
            EntityType.NAUTILUS,
            EntityType.ZOMBIE_NAUTILUS,
            EntityType.LLAMA,
            EntityType.TRADER_LLAMA,
            EntityType.HAPPY_GHAST
    ));

    // ─── State ────────────────────────────────────────────────────────────────

    private static final Set<UUID> currentlyTeleporting =
            Collections.synchronizedSet(new HashSet<>());

    private record PendingData(Entity vehicle) {}

    private static final Map<UUID, PendingData> pendingTeleports =
            Collections.synchronizedMap(new HashMap<>());

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Called at HEAD of player's teleport.
     * Only checks eligibility (supported type, blacklist, saddle, permissions, cross-dim).
     * Safety and border checks are done in onPostTeleport AFTER the player
     * arrives, because destination chunks are not yet loaded at HEAD time.
     */
    public static void onPreTeleport(ServerPlayer player, ServerLevel targetLevel, Vec3 targetPos) {
        Entity vehicle = getEligibleVehicle(player, targetLevel);
        if (vehicle == null) return;

        pendingTeleports.put(player.getUUID(), new PendingData(vehicle));
        TpWithMe.LOGGER.debug("{} Pre-teleport: captured {} for player {}.",
                TpWithMe.prefix(),
                vehicle.getType().toShortString(), player.getName().getString());
    }

    public static ServerPlayer handleMountedPearlTeleport(ServerPlayer player, TeleportTransition original) {
        if (!TpWithMeConfig.get().enderPearlTeleport) {
            return player.teleport(original);
        }

        if (!PermissionManager.canEnderPearlTeleport(player)) {
            return player.teleport(original);
        }

        Entity vehicle = getEligibleVehicle(player, original.newLevel());
        if (vehicle == null) {
            return player.teleport(original);
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

        Entity newVehicle = teleportVehicleRoot(player, vehicle, original.newLevel(), mountPos);
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

            Entity newVehicle = teleportVehicleRoot(player, vehicle, level, mountPos);
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

        return false;
    }

    public static void onPostTeleport(ServerPlayer player, ServerLevel targetLevel, Vec3 targetPos) {
        PendingData data = pendingTeleports.remove(player.getUUID());
        if (data == null) return;

        Entity vehicle = data.vehicle;

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
                RemountWatcher.schedule(player, newVehicle.getUUID());
            }

        } finally {
            currentlyTeleporting.remove(vehicleId);
        }
    }

    public static void cancelPending(UUID playerUUID) {
        pendingTeleports.remove(playerUUID);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static boolean isSupportedType(Entity entity) {
        return SUPPORTED_TYPES.contains(entity.getType());
    }

    private static Entity teleportVehicleRoot(ServerPlayer player, Entity vehicle, ServerLevel targetLevel, Vec3 mountPos) {
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

            RemountWatcher.schedule(player, newVehicle.getUUID());
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
            if (belowState.blocksMotion()) {
                foundGround = true;
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
