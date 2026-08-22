package dev.liquidcopy.client;

import dev.liquidcopy.api.module.Module;
import dev.liquidcopy.client.render.HudRenderContext;
import dev.liquidcopy.client.render.HudRenderable;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

final class HudController {
    private HudController() {
    }

    static void render(ClientKernel.RuntimeContext context, Object graphics, Object deltaTracker) {
        if (!(context.minecraft() instanceof Minecraft minecraft)
            || !(graphics instanceof GuiGraphics guiGraphics)
            || !(deltaTracker instanceof DeltaTracker deltas)) {
            return;
        }
        if (minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        HudRenderContext hud = new HudRenderContext(
            context.tick(), minecraft, guiGraphics, deltas, context.kernel().modules().enabled()
        );
        for (Module module : context.kernel().modules().enabled()) {
            if (module instanceof HudRenderable renderable) {
                try {
                    renderable.renderHud(hud);
                } catch (RuntimeException failure) {
                    System.err.println("[LiquidCopy] HUD render failed for " + module.id() + ": " + failure);
                }
            }
        }
    }
}
