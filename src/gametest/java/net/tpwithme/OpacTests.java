package net.tpwithme;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.compat.OpenPartiesAndClaimsCompat;
import net.tpwithme.compat.OpenPartiesAndClaimsCompat.TeleportType;
import net.tpwithme.config.TpWithMeConfig;
import net.tpwithme.handler.TeleportHandler;
import net.tpwithme.handler.PendingMountTeleports;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.player.config.api.v2.IPlayerConfigOptionSpecAPI;
import xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions;
import xaero.pac.common.parties.party.member.PartyMemberRank;

import java.util.UUID;

import static net.tpwithme.RegressionTests.*;

/** Loaded only in the -PtestOpac run, against the real OPAC jar. */
final class OpacTests {
    @SuppressWarnings("unchecked")
    static void run(GameTestHelper h, ServerPlayer player, Entity horse, Vec3 destination) {
        var api = OpenPACServerAPI.get(h.getLevel().getServer());
        var claims = api.getServerClaimsManager();
        var dimension = h.getLevel().dimension().identifier();
        BlockPos pos = BlockPos.containing(destination);
        int chunkX = pos.getX() >> 4, chunkZ = pos.getZ() >> 4;
        UUID owner = UUID.randomUUID();
        // OPAC can force these options to the server defaults; change that real config.
        var config = api.getPlayerConfigManager().getDefaultConfig();
        var pearl = (IPlayerConfigOptionSpecAPI<String>) api.getPlayerConfigManager()
                .getOptionForId("claims.protection.exceptions.groups.entity.barrier.Ender_Pearls");
        check(h, pearl != null, "OPAC exposes the real Ender_Pearls barrier option");
        boolean oldProtection = config.getEffective(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS);
        String oldChorus = config.getEffective(PlayerConfigOptions.CLAIM_EXCEPTION_CHORUS_FRUIT);
        String oldPearl = config.getEffective(pearl);
        var party = api.getPartyManager().createPartyForOwner(new NameAndId(owner, "TpWithMeOwner"));
        check(h, party != null, "OPAC test party created");
        try {
            check(h, claims.claim(dimension, owner, 0, chunkX, chunkZ, false) != null, "OPAC test claim created");
            config.tryToSet(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS, true);
            config.tryToSet(PlayerConfigOptions.CLAIM_EXCEPTION_CHORUS_FRUIT, "N");
            config.tryToSet(pearl, "N");
            var ownerConfig = api.getPlayerConfigManager().getLoadedConfig(owner);
            ownerConfig.tryToSet(pearl, "N");
            check(h, "N".equals(ownerConfig.getEffective(pearl)), "OPAC effective owner config denies pearl");
            for (TeleportType type : TeleportType.values()) {
                check(h, !OpenPartiesAndClaimsCompat.canTeleportInto(h.getLevel(), pos, player, type), "OPAC stranger denied: " + type);
            }

            Vec3 oldHorsePos = horse.position();
            mount(player, horse);
            player.teleport(transition(player, destination.add(0, 0.37, 0)));
            check(h, player.getVehicle() == null && horse.position().equals(oldHorsePos),
                    "OPAC denies general teleport carry after safe-ground adjustment");
            player.snapTo(destination);
            PendingMountTeleports.schedule(player, horse, destination);
            PendingMountTeleports.tick(h.getLevel().getServer());
            check(h, player.getVehicle() == null && horse.position().equals(oldHorsePos),
                    "deferred mount teleport rechecks OPAC and denies visitor");
            mount(player, horse);
            TeleportHandler.handleMountedPearlTeleport(player, transition(player, destination));
            check(h, player.getVehicle() == null && horse.position().equals(oldHorsePos), "OPAC-denied mounted pearl keeps mount behind");
            check(h, player.position().distanceToSqr(destination) < 0.01, "OPAC-denied mount preserves vanilla player teleport");
            mount(player, horse);
            TeleportHandler.handleMountedPearlTeleport(player, transition(player, destination.add(0, 0.37, 0)));
            check(h, player.getVehicle() == null && horse.position().equals(oldHorsePos),
                    "OPAC still denies mount after fractional landing is adjusted to safe ground");

            config.tryToSet(PlayerConfigOptions.CLAIM_EXCEPTION_CHORUS_FRUIT, "E");
            config.tryToSet(pearl, "E");
            ownerConfig.tryToSet(pearl, "E");
            check(h, "E".equals(api.getChunkProtection().getConfig(claims.get(dimension, pos), dimension)
                    .getEffective(PlayerConfigOptions.CLAIM_EXCEPTION_CHORUS_FRUIT)), "OPAC effective claim config has everyone chorus exception");
            check(h, OpenPartiesAndClaimsCompat.canTeleportInto(h.getLevel(), pos, player, TeleportType.CHORUS_FRUIT), "OPAC chorus everyone exception allows stranger");
            check(h, OpenPartiesAndClaimsCompat.canTeleportInto(h.getLevel(), pos, player, TeleportType.ENDER_PEARL), "OPAC pearl everyone exception allows stranger");
            check(h, !OpenPartiesAndClaimsCompat.canTeleportInto(h.getLevel(), pos, player, TeleportType.GENERAL), "OPAC mechanic exceptions do not grant general access");
            mount(player, horse);
            TeleportHandler.handleMountedPearlTeleport(player, transition(player, destination));
            check(h, player.getVehicle() == horse && horse.position().distanceToSqr(destination) < 0.01, "OPAC-allowed mounted pearl carries mount");
            TeleportHandler.handleMountedPearlTeleport(player, transition(player, destination.add(0, 0.37, 0)));
            check(h, player.getVehicle() == horse && horse.position().distanceToSqr(destination) < 0.01,
                    "OPAC-allowed fractional pearl carries mount to adjusted safe ground");

            config.tryToSet(PlayerConfigOptions.CLAIM_EXCEPTION_CHORUS_FRUIT, "N");
            check(h, Boolean.FALSE.equals(TeleportHandler.handleMountedChorusFruitTeleport(h.getLevel(), player, 0)), "mounted chorus respects OPAC deny");
            config.tryToSet(PlayerConfigOptions.CLAIM_EXCEPTION_CHORUS_FRUIT, "E");
            check(h, Boolean.TRUE.equals(TeleportHandler.handleMountedChorusFruitTeleport(h.getLevel(), player, 0)), "mounted chorus respects OPAC allow");

            config.tryToSet(pearl, "P");
            ownerConfig.tryToSet(pearl, "P");
            check(h, !OpenPartiesAndClaimsCompat.canTeleportInto(h.getLevel(), pos, player, TeleportType.ENDER_PEARL), "party-only pearl denies outsider");
            party.addMember(player.getUUID(), PartyMemberRank.MEMBER, player.getName().getString());
            check(h, OpenPartiesAndClaimsCompat.canTeleportInto(h.getLevel(), pos, player, TeleportType.ENDER_PEARL), "party-only pearl allows party member");
            party.removeMember(player.getUUID());

            claims.claim(dimension, player.getUUID(), 0, chunkX, chunkZ, false);
            for (TeleportType type : TeleportType.values()) {
                check(h, OpenPartiesAndClaimsCompat.canTeleportInto(h.getLevel(), pos, player, type), "OPAC owner allowed: " + type);
            }
            mount(player, horse);
            player.teleport(transition(player, destination.add(0, 0.37, 0)));
            check(h, player.getVehicle() == horse && horse.position().distanceToSqr(destination) < 0.01,
                    "OPAC owner general teleport carries mount to corrected ground");
            player.stopRiding();
            player.snapTo(destination);
            PendingMountTeleports.schedule(player, horse, destination);
            PendingMountTeleports.tick(h.getLevel().getServer());
            check(h, player.getVehicle() == horse, "deferred mount teleport allows OPAC owner");

            // Fault injection exercises the same catch path as an incompatible reflective API.
            check(h, !OpenPartiesAndClaimsCompat.canTeleportInto(null, pos, player, TeleportType.GENERAL), "OPAC integration failure denies carry");
            check(h, !OpenPartiesAndClaimsCompat.canTeleportInto(h.getLevel(), pos, player, TeleportType.GENERAL), "OPAC failure stays closed without repeated API calls");
            TpWithMeConfig.get().respectOpenPartiesAndClaims = false;
            mount(player, horse);
            player.teleport(transition(player, destination));
            check(h, player.getVehicle() == horse, "explicit OPAC opt-out remains functional");
        } finally {
            config.tryToSet(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS, oldProtection);
            config.tryToSet(PlayerConfigOptions.CLAIM_EXCEPTION_CHORUS_FRUIT, oldChorus);
            config.tryToSet(pearl, oldPearl);
            TpWithMeConfig.get().respectOpenPartiesAndClaims = true;
            OpenPartiesAndClaimsCompat.reset();
            claims.unclaim(dimension, chunkX, chunkZ);
            api.getPartyManager().removeParty(party);
        }
    }
}
