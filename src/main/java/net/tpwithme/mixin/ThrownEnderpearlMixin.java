package net.tpwithme.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.portal.TeleportTransition;
import net.tpwithme.handler.TeleportHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ThrownEnderpearl.class)
public class ThrownEnderpearlMixin {

    @WrapOperation(
            method = "onHit(Lnet/minecraft/world/phys/HitResult;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;"
            )
    )
    private ServerPlayer tpwithme$handlePearlTeleport(ServerPlayer player, TeleportTransition transition,
                                                     Operation<ServerPlayer> original) {
        // Keep the replacement composable with outer pearl handlers such as LeashedTeleport.
        return TeleportHandler.handleMountedPearlTeleport(player, transition);
    }
}
