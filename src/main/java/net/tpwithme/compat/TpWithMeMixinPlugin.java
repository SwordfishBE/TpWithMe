package net.tpwithme.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

import java.util.List;

public final class TpWithMeMixinPlugin implements IMixinConfigPlugin {
    @Override
    public List<String> getMixins() {
        // Do not offer the optional target to Mixin when Moonrise is absent.
        return FabricLoader.getInstance().isModLoaded("moonrise")
                ? List.of("MoonriseEntityLookupAccessor") : List.of();
    }
}
