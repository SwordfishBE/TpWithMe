package net.tpwithme;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.Visibility;
import net.tpwithme.mixin.MoonriseEntityLookupAccessor;
import net.tpwithme.mixin.ServerLevelEntityManagerAccessor;

/** Exercise the active entity manager rather than Moonrise's unused vanilla storage. */
final class EntityVisibilityTestSupport {
    static void setVisibility(ServerLevel level, ChunkPos chunk, Visibility visibility) {
        var accessor = (ServerLevelEntityManagerAccessor) level;
        var getter = accessor.tpwithme$getEntityGetter();
        if (FabricLoader.getInstance().isModLoaded("moonrise") && getter instanceof MoonriseEntityLookupAccessor) {
            var status = switch (visibility) {
                case HIDDEN -> FullChunkStatus.INACCESSIBLE;
                case TRACKED -> FullChunkStatus.FULL;
                case TICKING -> FullChunkStatus.ENTITY_TICKING;
            };
            try {
                getter.getClass().getMethod("chunkStatusChange", int.class, int.class, FullChunkStatus.class)
                        .invoke(getter, chunk.x(), chunk.z(), status);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot change Moonrise test entity visibility", e);
            }
        } else {
            accessor.tpwithme$getEntityManager().updateChunkStatus(chunk, visibility);
        }
    }
}
