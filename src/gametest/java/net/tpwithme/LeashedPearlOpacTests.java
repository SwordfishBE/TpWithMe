package net.tpwithme;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.config.TpWithMeConfig;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.player.config.api.v2.IPlayerConfigOptionSpecAPI;
import xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions;

import java.util.Map;
import java.util.UUID;

import static net.tpwithme.RegressionTests.*;

/** Tests actual pearl impacts against real OPAC rules for the separately leashed mob. */
final class LeashedPearlOpacTests {
    @SuppressWarnings("unchecked")
    static void run(GameTestHelper h, ServerPlayer player, Entity horse, Mob cow,
                    Vec3 horseSource, Vec3 cowSource, Vec3 target) throws Exception {
        var api = OpenPACServerAPI.get(h.getLevel().getServer());
        var claims = api.getServerClaimsManager();
        var dimension = h.getLevel().dimension().identifier();
        BlockPos block = BlockPos.containing(target);
        int x = block.getX() >> 4, z = block.getZ() >> 4;
        var defaults = api.getPlayerConfigManager().getDefaultConfig();
        var pearl = (IPlayerConfigOptionSpecAPI<String>) api.getPlayerConfigManager()
                .getOptionForId("claims.protection.exceptions.groups.entity.barrier.Ender_Pearls");
        check(h, pearl != null, "combined pearl fixture finds OPAC pearl rule");
        boolean oldProtection = defaults.getEffective(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS);
        String oldPearl = defaults.getEffective(pearl);
        boolean oldMountOpac = TpWithMeConfig.get().respectOpenPartiesAndClaims;
        UUID owner = UUID.randomUUID();
        var party = api.getPartyManager().createPartyForOwner(new NameAndId(owner, "LeashedPearlOwner"));
        var claimedChunks = new java.util.ArrayList<net.minecraft.world.level.ChunkPos>();
        try {
            // Rider offsets and safe-position search may cross a chunk edge from the horse's center.
            boolean allClaimed = true;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                boolean claimed = claims.claim(dimension, owner, 0, x + dx, z + dz, false) != null;
                allClaimed &= claimed;
                if (claimed) claimedChunks.add(new net.minecraft.world.level.ChunkPos(x + dx, z + dz));
            }
            check(h, allClaimed, "combined pearl fixture creates real claims covering the full landing search");
            defaults.tryToSet(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS, true);
            var ownerConfig = api.getPlayerConfigManager().getLoadedConfig(owner);
            // Leave the mount's mod check out; OPAC may still block the vanilla pearl itself.
            TpWithMeConfig.get().respectOpenPartiesAndClaims = false;
            var handler = Class.forName("net.leashedteleport.handler.LeashTeleportHandler");
            for (boolean allowed : new boolean[]{false, true}) {
                defaults.tryToSet(pearl, allowed ? "E" : "N");
                ownerConfig.tryToSet(pearl, allowed ? "E" : "N");
                LeashedPearlCompatibilityTests.reset(player, horse, cow, horseSource, cowSource);
                mount(player, horse);
                cow.setLeashedTo(player, true);
                LeashedPearlCompatibilityTests.impact(h, player, target);
                check(h, player.getVehicle() == horse && (horse.position().distanceToSqr(target) < 0.01
                                || (!allowed && horse.position().equals(horseSource))),
                        "OPAC-denied pearl is blocked or leaves leash behind; allowed mounted pearl arrives");
                if (!allowed && !cow.position().equals(cowSource)) {
                    TpWithMe.LOGGER.warn("Denied pearl fixture mismatch: player={}, mount={}, cow={}, source={}, target={}, claim={}",
                            player.position(), horse.position(), cow.position(), cowSource, target,
                            claims.get(dimension, BlockPos.containing(cow.position())));
                }
                check(h, allowed ? cow.position().distanceToSqr(target) < 0.01 : cow.position().equals(cowSource),
                        "actual mounted pearl respects leashed mob OPAC pearl rule (allowed=" + allowed + ")");
                check(h, ((Map<?, ?>) field(handler, "PENDING_TELEPORTS").get(null)).isEmpty(),
                        "OPAC pearl handling consumes leash capture");
            }
            for (var chunk : claimedChunks) claims.unclaim(dimension, chunk.x(), chunk.z());
            claimedChunks.clear();
            int boundary = (int) (Math.floor((target.x + 8) / 16) * 16);
            Vec3 edge = new Vec3(boundary - 0.1, target.y, target.z);
            int edgeX = boundary >> 4, edgeZ = BlockPos.containing(edge).getZ() >> 4;
            check(h, claims.claim(dimension, owner, 0, edgeX, edgeZ, false) != null,
                    "leashed footprint fixture creates neighboring claim");
            claimedChunks.add(new net.minecraft.world.level.ChunkPos(edgeX, edgeZ));
            check(h, claims.get(dimension, BlockPos.containing(edge)) == null,
                    "leashed mob center lands in wilderness beside protected chunk");
            LeashedPearlCompatibilityTests.reset(player, horse, cow, horseSource, cowSource);
            var safety = Class.forName("net.leashedteleport.safety.TeleportSafetyChecker");
            check(h, edge.equals(safety.getMethod("findSafeLocation", net.minecraft.server.level.ServerLevel.class,
                            Mob.class, double.class, double.class, double.class)
                    .invoke(null, h.getLevel(), cow, edge.x, edge.y, edge.z)),
                    "claim-edge fixture has safe exact terrain, not a blocked-space rejection");
            cow.setLeashedTo(player, true);
            handler.getMethod("capturePendingTeleport", ServerPlayer.class).invoke(null, player);
            player.snapTo(edge);
            handler.getMethod("handlePendingTeleport", ServerPlayer.class).invoke(null, player);
            check(h, cow.position().equals(cowSource),
                    "real OPAC denies leashed body overlapping neighboring claim despite wilderness center");
        } finally {
            TpWithMeConfig.get().respectOpenPartiesAndClaims = oldMountOpac;
            defaults.tryToSet(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS, oldProtection);
            defaults.tryToSet(pearl, oldPearl);
            for (var chunk : claimedChunks) claims.unclaim(dimension, chunk.x(), chunk.z());
            if (party != null) api.getPartyManager().removeParty(party);
        }
    }
}
