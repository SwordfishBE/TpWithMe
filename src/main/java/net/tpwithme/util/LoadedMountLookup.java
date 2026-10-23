package net.tpwithme.util;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.mixin.PersistentEntitySectionManagerAccessor;
import net.tpwithme.mixin.ServerLevelEntityManagerAccessor;
import net.tpwithme.mixin.MoonriseEntityLookupAccessor;

import java.util.UUID;

/** Resolves loaded mounts even when their section is temporarily hidden from tracking. */
public final class LoadedMountLookup {
    private LoadedMountLookup() {}

    public static Entity find(ServerLevel level, UUID id, Vec3 lastPosition) {
        if (level == null) return null;
        Entity visible = level.getEntity(id);
        if (visible != null) return visible;
        if (!(level instanceof ServerLevelEntityManagerAccessor accessor)) return null;
        if (FabricLoader.getInstance().isModLoaded("moonrise")
                && accessor.tpwithme$getEntityGetter() instanceof MoonriseEntityLookupAccessor moonrise) {
            return moonrise.tpwithme$getLoadedEntitiesByUUID().get(id);
        }
        var manager = accessor.tpwithme$getEntityManager();
        if (manager == null) return null;
        var sections = ((PersistentEntitySectionManagerAccessor) manager).tpwithme$getSectionStorage();
        if (sections == null) return null;
        // Pending transfers already reject movement beyond one block: inspect at most eight sections.
        for (int x = Mth.floor(lastPosition.x - 1) >> 4; x <= Mth.floor(lastPosition.x + 1) >> 4; x++) {
            for (int y = Mth.floor(lastPosition.y - 1) >> 4; y <= Mth.floor(lastPosition.y + 1) >> 4; y++) {
                for (int z = Mth.floor(lastPosition.z - 1) >> 4; z <= Mth.floor(lastPosition.z + 1) >> 4; z++) {
                    var section = sections.getSection(SectionPos.asLong(x, y, z));
                    if (section == null) continue;
                    Entity found = section.getEntities().filter(entity -> entity.getUUID().equals(id)).findFirst().orElse(null);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }
}
