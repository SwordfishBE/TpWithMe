package net.tpwithme.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;
import net.minecraft.world.level.Level;
import net.tpwithme.handler.TeleportHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TeleportRandomlyConsumeEffect.class)
public abstract class TeleportRandomlyConsumeEffectMixin {

    @Shadow
    public abstract float diameter();

    @Inject(
            method = "apply(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void tpwithme$handleMountedChorusFruit(
            Level level,
            ItemStack stack,
            LivingEntity livingEntity,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        if (!(livingEntity instanceof ServerPlayer player)) {
            return;
        }

        Boolean handled = TeleportHandler.handleMountedChorusFruitTeleport(serverLevel, player, diameter());
        if (handled != null) {
            cir.setReturnValue(handled);
        }
    }
}
