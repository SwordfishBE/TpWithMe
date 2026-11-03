package net.tpwithme;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.TitleScreen;

/** No Cloth type references: the same test must load when Cloth is absent. */
public final class ClothConfigNoticeClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        boolean installed = FabricLoader.getInstance().isModLoaded("cloth-config");
        if (TpWithMeModMenuIntegration.hasCompatibleClothConfig()) {
            throw new AssertionError("This fixture expects missing or old Cloth Config");
        }
        TitleScreen[] parent = {null};
        context.runOnClient(client -> {
            parent[0] = new TitleScreen();
            var screen = new TpWithMeModMenuIntegration().getModConfigScreenFactory().create(parent[0]);
            if (!(screen instanceof AlertScreen)) throw new AssertionError("Expected safe Cloth Config notice");
            client.setScreenAndShow(screen);
        });
        context.waitTicks(5);
        context.takeScreenshot(installed ? "tpwithme-cloth-too-old" : "tpwithme-cloth-missing");
        context.clickScreenButton("gui.back");
        context.runOnClient(client -> {
            if (client.gui.screen() != parent[0]) throw new AssertionError("Back must restore the supplied parent screen");
        });
        context.waitTicks(3);
    }
}
