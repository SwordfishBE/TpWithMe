package net.tpwithme.util;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;

/** Briefly retains already loaded source terrain while a mount transfer is in flight. */
public final class MountSourceChunkGuard {
    private static final TicketType SOURCE = Registry.register(BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath("tpwithme", "mount_transfer_source"),
            new TicketType(120L, TicketType.FLAG_LOADING));

    private MountSourceChunkGuard() {}

    public static void register() {
        // Initialize the ticket type during mod init, before built-in registries freeze.
    }

    public static void keepLoaded(Entity vehicle) {
        if (!(vehicle.level() instanceof ServerLevel level) || vehicle.isRemoved()) return;
        var chunk = vehicle.chunkPosition();
        if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
            // Radius zero retains this chunk only; the nonpersistent ticket expires after six seconds.
            level.getChunkSource().addTicketWithRadius(SOURCE, chunk, 0);
        }
    }
}
