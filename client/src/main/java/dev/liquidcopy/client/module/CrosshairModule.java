package dev.liquidcopy.client.module;

import dev.liquidcopy.api.module.ModuleCategory;
import dev.liquidcopy.api.module.ModuleMetadata;
import dev.liquidcopy.api.setting.BooleanSetting;
import dev.liquidcopy.api.setting.ColorSetting;
import dev.liquidcopy.api.setting.NumberSetting;
import dev.liquidcopy.client.render.HudRenderContext;
import dev.liquidcopy.client.render.HudRenderable;
import net.minecraft.client.gui.GuiGraphics;
import dev.liquidcopy.api.setting.KeybindSetting;

public final class CrosshairModule extends MinecraftModule implements HudRenderable {
    private final ColorSetting color = setting(new ColorSetting("color", "Color", 0xFFFFFFFF));
    private final NumberSetting size = setting(new NumberSetting("size", "Size", 5.0D, 2.0D, 15.0D, 1.0D));
    private final NumberSetting gap = setting(new NumberSetting("gap", "Gap", 2.0D, 0.0D, 8.0D, 1.0D));
    private final NumberSetting thickness = setting(new NumberSetting("thickness", "Thickness", 1.0D, 1.0D, 4.0D, 1.0D));
    private final BooleanSetting outline = setting(new BooleanSetting("outline", "Outline", true));

    public CrosshairModule() {
        super(new ModuleMetadata(
            "crosshair", "Crosshair", "Draws a configurable high-contrast HUD crosshair.",
            ModuleCategory.RENDER, false
        ), KeybindSetting.UNBOUND);
    }

    @Override
    public void renderHud(HudRenderContext context) {
        GuiGraphics graphics = context.graphics();
        int x = graphics.guiWidth() / 2;
        int y = graphics.guiHeight() / 2;
        int arm = size.value().intValue();
        int centerGap = gap.value().intValue();
        int line = thickness.value().intValue();
        if (outline.value()) {
            draw(graphics, x, y, arm + 1, centerGap, line + 2, 0xDD000000);
        }
        draw(graphics, x, y, arm, centerGap, line, color.value());
    }

    private static void draw(GuiGraphics graphics, int x, int y, int size, int gap, int thickness, int color) {
        int half = thickness / 2;
        graphics.fill(x - half, y - gap - size, x - half + thickness, y - gap, color);
        graphics.fill(x - half, y + gap, x - half + thickness, y + gap + size, color);
        graphics.fill(x - gap - size, y - half, x - gap, y - half + thickness, color);
        graphics.fill(x + gap, y - half, x + gap + size, y - half + thickness, color);
    }
}
