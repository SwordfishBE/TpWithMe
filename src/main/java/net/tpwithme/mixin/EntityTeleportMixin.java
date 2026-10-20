package net.tpwithme.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.tpwithme.handler.RemountWatcher;
import net.tpwithme.handler.TeleportHandler;
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
        RemountWatcher.cancel(player.getUUID());
        long sequence = ++tpwithme$teleportSequence;
        // Captured entities live only on this invocation's stack, including on exceptions.
        Entity vehicle = TeleportHandler.captureVehicle(player, transition.newLevel());
        ServerPlayer returned = original.call(transition);
        if (returned != null && vehicle != null && sequence == tpwithme$teleportSequence) {
            TeleportHandler.onPostTeleport(returned, vehicle, returned.level(), returned.position());
        }
        return returned;
    }
}
