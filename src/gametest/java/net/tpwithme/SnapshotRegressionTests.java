package net.tpwithme;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.impl.game.minecraft.McVersionLookup;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.tpwithme.util.ModrinthUpdateChecker;
import net.tpwithme.util.SafetyChecker;

import static net.tpwithme.RegressionTests.check;

final class SnapshotRegressionTests {
    static void run(GameTestHelper h, Entity horse, Vec3 origin) throws Exception {
        var dependency = FabricLoader.getInstance().getModContainer("tpwithme").orElseThrow()
                .getMetadata().getDependencies().stream()
                .filter(entry -> entry.getModId().equals("minecraft")).findFirst().orElseThrow();
        for (String raw : new String[]{"26.4-snapshot-6", "26.4-snapshot-7", "26.4-snapshot-10",
                "26.4-pre-1", "26.4-rc-1", "26.4"}) {
            check(h, dependency.matches(normalize(raw)), "Minecraft range accepts " + raw);
        }
        for (String raw : new String[]{"26.3", "26.4-snapshot-1", "26.4-snapshot-5",
                "26.4.1", "26.5-snapshot-1", "26.5"}) {
            check(h, !dependency.matches(normalize(raw)), "Minecraft range rejects " + raw);
        }
        var currentVersion = ModrinthUpdateChecker.class.getDeclaredMethod("currentMinecraftVersion");
        currentVersion.setAccessible(true);
        check(h, SharedConstants.getCurrentVersion().id().equals(currentVersion.invoke(null)),
                "Modrinth lookup uses raw game ID rather than Loader-normalized snapshot version");

        var level = h.getLevel();
        BlockPos floor = BlockPos.containing(origin).below();
        BlockPos feet = floor.above();
        var oldFloor = level.getBlockState(floor);
        var oldFeet = level.getBlockState(feet);
        try {
            level.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
            for (var hazard : new net.minecraft.world.level.block.Block[]{
                    Blocks.POINTED_DRIPSTONE, Blocks.SULFUR_SPIKE, Blocks.ICICLE}) {
                var state = hazard.defaultBlockState();
                level.setBlockAndUpdate(floor, state);
                check(h, state.is(BlockTags.DANGEROUS_FOR_TELEPORTATION), "vanilla teleport hazard tag includes " + hazard);
                check(h, !SafetyChecker.isSafe(horse, level, origin), "mount rejects tagged sharp support " + hazard);
            }
            level.setBlockAndUpdate(floor, oldFloor);
            for (var fluid : new net.minecraft.world.level.block.Block[]{Blocks.WATER, Blocks.LAVA}) {
                var flowing = fluid.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 7);
                level.setBlockAndUpdate(feet, flowing);
                check(h, !flowing.getFluidState().isSource(), "snapshot fluid fixture is non-source " + fluid);
                check(h, !SafetyChecker.isSafe(horse, level, origin), "ordinary mount rejects flowing fluid contact " + fluid);
            }
            level.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(floor, Blocks.SNOW_BRICKS.defaultBlockState());
            check(h, SafetyChecker.isSafe(horse, level, origin), "new snow bricks support safe mount landings");
            level.setBlockAndUpdate(feet, Blocks.SNOW_BRICK_SLAB.defaultBlockState());
            check(h, SafetyChecker.isSafe(horse, level, origin.add(0, 0.5, 0)),
                    "new snow brick slab uses its fractional collision surface");
        } finally {
            level.setBlockAndUpdate(feet, oldFeet);
            level.setBlockAndUpdate(floor, oldFloor);
        }
    }

    private static Version normalize(String raw) throws Exception {
        return Version.parse(McVersionLookup.normalizeVersion(raw, McVersionLookup.getRelease(raw)));
    }
}
