package net.tpwithme.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.tpwithme.TpWithMe;
import net.tpwithme.handler.TeleportHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin on ServerPlayer#teleport(TeleportTransition).
 *
 * HEAD  → safety check + capture vehicle BEFORE the teleport.
 *         We have access to TeleportTransition here, which contains the
 *         destination level and position. This is the correct place to
 *         block unsafe teleports before the player moves.
 *
 * RETURN → teleport the vehicle to the player's new position and remount.
 *          No safety check needed here — already done in HEAD.
 */
@Mixin(ServerPlayer.class)
public class EntityTeleportMixin {

    @Inject(
            method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At("HEAD")
    )
    private void tpwithme$preTeleport(
            TeleportTransition transition,
            CallbackInfoReturnable<ServerPlayer> cir
    ) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        TpWithMe.LOGGER.debug("[TpWithMe] HEAD: teleport() called for player {}, vehicle={}",
                player.getName().getString(),
                player.getVehicle() != null ? player.getVehicle().getType().toShortString() : "none");

        // Pass the transition so onPreTeleport can safety-check the destination
        // BEFORE the player moves.
        ServerLevel targetLevel = transition.newLevel();
        TeleportHandler.onPreTeleport(player, targetLevel, transition.position());
    }

    @Inject(
            method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
            at = @At("RETURN")
    )
    private void tpwithme$postTeleport(
            TeleportTransition transition,
            CallbackInfoReturnable<ServerPlayer> cir
    ) {
        ServerPlayer returned = cir.getReturnValue();

        if (returned == null) {
            ServerPlayer self = (ServerPlayer) (Object) this;
            TpWithMe.LOGGER.debug("[TpWithMe] RETURN: null – cancelling pending for {}",
                    self.getName().getString());
            TeleportHandler.cancelPending(self.getUUID());
            return;
        }

        TpWithMe.LOGGER.debug("[TpWithMe] RETURN: teleport() finished for player {}",
                returned.getName().getString());

        ServerLevel newLevel = (ServerLevel) returned.level();
        TeleportHandler.onPostTeleport(returned, newLevel, returned.position());
    }
}
