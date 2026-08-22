package dev.liquidcopy.client.render;

import dev.liquidcopy.api.module.Module;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public record HudRenderContext(
    long tick,
    Minecraft minecraft,
    GuiGraphics graphics,
    DeltaTracker deltaTracker,
    List<Module> enabledModules
) {
    public HudRenderContext {
        enabledModules = List.copyOf(enabledModules);
    }

    public float partialTick() {
        return deltaTracker.getGameTimeDeltaPartialTick(true);
    }
}
