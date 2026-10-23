package net.tpwithme.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.tpwithme.handler.RemountWatcher;
import net.tpwithme.handler.TeleportHandler;
import net.tpwithme.handler.PendingMountTeleports;
import net.tpwithme.util.MountSourceChunkGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerPlayer.class)
public class EntityTeleportMixin {
    @Unique
    private long tpwithme$teleportSequence;

    @WrapMethod(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;")
    private ServerPlayer tpwithme$teleport(TeleportTransition transition, Operation<ServerPlayer> original) {
        if (transition.asPassenger()) return original.call(transition);
        ServerPlayer player = (ServerPlayer) (Object) this;
        long sequence = ++tpwithme$teleportSequence;
        // Captured entities live only on this invocation's stack, including on exceptions.
        Entity vehicle = TeleportHandler.captureVehicle(player, transition.newLevel());
        boolean waiting = vehicle == null && PendingMountTeleports.isWaitingForMount(player);
        if (waiting) RemountWatcher.cancelRecovery(player.getUUID());
        else RemountWatcher.cancel(player.getUUID());
        if (vehicle != null) MountSourceChunkGuard.keepLoaded(vehicle);
        boolean completed = false;
        try {
            ServerPlayer returned = original.call(transition);
            if (returned != null && sequence == tpwithme$teleportSequence) {
                if (waiting) PendingMountTeleports.retarget(returned);
                else if (vehicle != null) TeleportHandler.onPostTeleport(returned, vehicle, returned.level(), returned.position());
            }
            completed = returned != null;
            return returned;
        } finally {
            if (waiting && !completed && sequence == tpwithme$teleportSequence) PendingMountTeleports.cancel(player.getUUID());
        }
    }
}
