package net.tpwithme.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;
import net.minecraft.world.level.Level;
import net.tpwithme.handler.TeleportHandler;
import net.tpwithme.handler.RemountWatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TeleportRandomlyConsumeEffect.class)
public abstract class TeleportRandomlyConsumeEffectMixin {

    @Shadow
    public abstract float diameter();

    @WrapMethod(method = "apply(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)Z")
    private boolean tpwithme$handleMountedChorusFruit(
            Level level,
            ItemStack stack,
            LivingEntity livingEntity,
            Operation<Boolean> original
    ) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return original.call(level, stack, livingEntity);
        }

        if (!(livingEntity instanceof ServerPlayer player)) {
            return original.call(level, stack, livingEntity);
        }

        Boolean handled = TeleportHandler.handleMountedChorusFruitTeleport(serverLevel, player, diameter());
        if (handled != null) {
            return handled;
        }
        boolean teleported = original.call(level, stack, livingEntity);
        // Vanilla chorus bypasses the general TeleportTransition wrapper.
        if (teleported) RemountWatcher.cancel(player.getUUID());
        return teleported;
    }
}
