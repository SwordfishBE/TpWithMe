package net.tpwithme.testmixin;

import net.minecraft.world.entity.Mob;
import net.tpwithme.AsyncCompatibilityTests;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
abstract class AsyncMobTickProbeMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void tpwithme$observeWorkerTick(CallbackInfo ci) {
        if (AsyncCompatibilityTests.onMobTick((Mob) (Object) this)) ci.cancel();
    }
}
