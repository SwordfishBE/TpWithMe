package net.tpwithme;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.compat.OpenPartiesAndClaimsCompat;
import net.tpwithme.compat.OpenPartiesAndClaimsCompat.TeleportType;
import net.tpwithme.handler.PendingMountTeleports;
import net.tpwithme.handler.RemountWatcher;
import net.tpwithme.handler.TeleportHandler;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.player.config.api.v2.IPlayerConfigOptionSpecAPI;
import xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions;

import java.util.Map;

import static net.tpwithme.RegressionTests.*;

/** Multi-rider checks against real OPAC; loaded only in the OPAC test run. */
final class PassengerOpacTests {
    @SuppressWarnings("unchecked")
    static void run(GameTestHelper h, ServerPlayer owner, Vec3 origin, Vec3 destination) throws Exception {
        var level = h.getLevel();
        var server = level.getServer();
        var api = OpenPACServerAPI.get(server);
        var claims = api.getServerClaimsManager();
        var dimension = level.dimension().identifier();
        BlockPos pos = BlockPos.containing(destination);
        var defaults = api.getPlayerConfigManager().getDefaultConfig();
        var pearl = (IPlayerConfigOptionSpecAPI<String>) api.getPlayerConfigManager()
                .getOptionForId("claims.protection.exceptions.groups.entity.barrier.Ender_Pearls");
        boolean oldProtection = defaults.getEffective(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS);
        String oldFull = defaults.getEffective(PlayerConfigOptions.FULL_ACCESS);
        String oldChorus = defaults.getEffective(PlayerConfigOptions.CLAIM_EXCEPTION_CHORUS_FRUIT);
        String oldPearl = defaults.getEffective(pearl);
        var party = api.getPartyManager().createPartyForOwner(new NameAndId(owner.getUUID(), owner.getName().getString()));
        var ownerConfig = api.getPlayerConfigManager().getLoadedConfig(owner.getUUID());
        String oldOwnerFull = ownerConfig.getEffective(PlayerConfigOptions.FULL_ACCESS);
        String oldOwnerPearl = ownerConfig.getEffective(pearl);
        var visitor = h.makeMockServerPlayerInLevel();
        var camel = h.spawn(EntityTypes.CAMEL, new Vec3(7.5, 1, 7.5));
        camel.setNoAi(true);
        camel.setNoGravity(true);
        Vec3 camelOrigin = camel.position();
        try {
            defaults.tryToSet(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS, true);
            defaults.tryToSet(PlayerConfigOptions.FULL_ACCESS, "N");
            ownerConfig.tryToSet(PlayerConfigOptions.FULL_ACCESS, "N");
            defaults.tryToSet(PlayerConfigOptions.CLAIM_EXCEPTION_CHORUS_FRUIT, "N");
            defaults.tryToSet(pearl, "N");
            ownerConfig.tryToSet(pearl, "N");
            check(h, claims.claim(dimension, owner.getUUID(), 0, pos.getX() >> 4, pos.getZ() >> 4, false) != null,
                    "multi-rider OPAC claim created");
            for (TeleportType type : TeleportType.values()) {
                check(h, !OpenPartiesAndClaimsCompat.canTeleportInto(level, pos, visitor, type),
                        "second rider independently denied by OPAC: " + type);
            }
            mount(owner, camel);
            visitor.snapTo(camelOrigin);
            check(h, visitor.startRiding(camel, true, false), "OPAC test camel has second rider");
            owner.teleport(transition(owner, destination));
            check(h, owner.getVehicle() == null && visitor.getVehicle() == camel && camel.position().equals(camelOrigin),
                    "general carry cannot bring OPAC-denied second rider into claim");
            mount(owner, camel);
            TeleportHandler.handleMountedPearlTeleport(owner, transition(owner, destination));
            check(h, owner.getVehicle() == null && visitor.getVehicle() == camel && camel.position().equals(camelOrigin),
                    "pearl carry cannot bring OPAC-denied second rider into claim");

            camel.snapTo(destination);
            visitor.snapTo(destination);
            mount(owner, camel);
            owner.snapTo(destination.add(0, 2.5, 0));
            check(h, Boolean.FALSE.equals(TeleportHandler.handleMountedChorusFruitTeleport(level, owner, 0)),
                    "chorus carry checks second rider claim access");
            owner.stopRiding();
            owner.snapTo(destination);
            PendingMountTeleports.schedule(owner, camel, destination);
            PendingMountTeleports.tick(server);
            check(h, owner.getVehicle() == null && visitor.getVehicle() == camel,
                    "deferred carry checks second rider claim access");
            PendingMountTeleports.schedule(owner, camel, destination);
            owner.teleport(transition(owner, destination.add(0, 0.37, 0)));
            check(h, owner.getVehicle() == null && visitor.getVehicle() == camel,
                    "retargeted terrain wait still rejects OPAC-denied co-rider");

            defaults.tryToSet(PlayerConfigOptions.FULL_ACCESS, "E");
            ownerConfig.tryToSet(PlayerConfigOptions.FULL_ACCESS, "E");
            check(h, OpenPartiesAndClaimsCompat.canTeleportInto(level, pos, visitor, TeleportType.GENERAL),
                    "second rider granted real OPAC access");
            mount(owner, camel);
            owner.teleport(transition(owner, destination.add(0, 0.37, 0)));
            check(h, owner.getVehicle() == camel && visitor.getVehicle() == camel,
                    "general carry preserves both OPAC-authorized riders");
            TeleportHandler.handleMountedPearlTeleport(owner, transition(owner, destination));
            check(h, owner.getVehicle() == camel && visitor.getVehicle() == camel,
                    "pearl carry preserves both OPAC-authorized riders");
            owner.snapTo(destination.add(0, 2.5, 0));
            check(h, Boolean.TRUE.equals(TeleportHandler.handleMountedChorusFruitTeleport(level, owner, 0))
                            && owner.getVehicle() == camel && visitor.getVehicle() == camel,
                    "chorus carry preserves both OPAC-authorized riders");
            owner.stopRiding();
            owner.snapTo(destination);
            PendingMountTeleports.schedule(owner, camel, destination);
            PendingMountTeleports.tick(server);
            check(h, owner.getVehicle() == camel && visitor.getVehicle() == camel,
                    "deferred carry preserves original OPAC-authorized co-rider");

            owner.stopRiding();
            owner.snapTo(destination);
            PendingMountTeleports.schedule(owner, camel, destination);
            defaults.tryToSet(PlayerConfigOptions.FULL_ACCESS, "N");
            ownerConfig.tryToSet(PlayerConfigOptions.FULL_ACCESS, "N");
            PendingMountTeleports.tick(server);
            check(h, owner.getVehicle() == null && visitor.getVehicle() == camel
                            && ((Map<?, ?>) field(PendingMountTeleports.class, "pending").get(null)).isEmpty(),
                    "OPAC access revoked during wait prevents co-rider carry");
        } finally {
            camel.ejectPassengers();
            camel.discard();
            server.getPlayerList().remove(visitor);
            claims.unclaim(dimension, pos.getX() >> 4, pos.getZ() >> 4);
            ownerConfig.tryToSet(PlayerConfigOptions.FULL_ACCESS, oldOwnerFull);
            ownerConfig.tryToSet(pearl, oldOwnerPearl);
            if (party != null) api.getPartyManager().removeParty(party);
            defaults.tryToSet(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS, oldProtection);
            defaults.tryToSet(PlayerConfigOptions.FULL_ACCESS, oldFull);
            defaults.tryToSet(PlayerConfigOptions.CLAIM_EXCEPTION_CHORUS_FRUIT, oldChorus);
            defaults.tryToSet(pearl, oldPearl);
            RemountWatcher.clear();
            owner.snapTo(origin);
        }
    }
}
