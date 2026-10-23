package net.tpwithme.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Optional read-only access to Moonrise's already loaded, including hidden, entities. */
@Pseudo
@Mixin(targets = "ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup", remap = false)
public interface MoonriseEntityLookupAccessor {
    @Accessor("entityByUUID")
    ConcurrentHashMap<UUID, Entity> tpwithme$getLoadedEntitiesByUUID();
}
