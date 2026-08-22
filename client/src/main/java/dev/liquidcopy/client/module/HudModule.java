package dev.liquidcopy.client.module;

import dev.liquidcopy.api.module.Module;
import dev.liquidcopy.api.module.ModuleCategory;
import dev.liquidcopy.api.module.ModuleMetadata;
import dev.liquidcopy.api.setting.BooleanSetting;
import dev.liquidcopy.api.setting.ColorSetting;
import dev.liquidcopy.api.setting.KeybindSetting;
import dev.liquidcopy.client.render.HudRenderContext;
import dev.liquidcopy.client.render.HudRenderable;
import java.util.Comparator;
import java.util.List;

public final class HudModule extends MinecraftModule implements HudRenderable {
    private final ColorSetting accent = setting(new ColorSetting("accent", "Accent", 0xFF4DE1FF));
    private final BooleanSetting watermark = setting(new BooleanSetting("watermark", "Watermark", true));
    private final BooleanSetting moduleList = setting(new BooleanSetting("module_list", "Module List", true));

    public HudModule() {
        super(new ModuleMetadata(
            "hud", "HUD", "Shows the LiquidCopy watermark and active-module array list.",
            ModuleCategory.CLIENT, true
        ), KeybindSetting.UNBOUND);
    }

    @Override
    public void renderHud(HudRenderContext context) {
        if (context.minecraft().options.hideGui) {
            return;
        }
        if (watermark.value()) {
            String text = "LiquidCopy 1.21.11";
            int width = context.minecraft().font.width(text);
            context.graphics().fill(3, 3, width + 9, 16, 0xB0000000);
            context.graphics().fill(3, 3, 5, 16, accent.value());
            context.graphics().drawString(context.minecraft().font, text, 7, 6, 0xFFFFFFFF, true);
        }
        if (!moduleList.value()) {
            return;
        }
        List<Module> enabled = context.enabledModules().stream()
                .filter(module -> module != this)
                .sorted(Comparator.comparingInt((Module module) ->
                    context.minecraft().font.width(module.metadata().name())).reversed())
                .toList();
        int y = 4;
        for (Module module : enabled) {
            String text = module.metadata().name();
            int textWidth = context.minecraft().font.width(text);
            int x = context.graphics().guiWidth() - textWidth - 7;
            context.graphics().fill(x - 2, y - 1, context.graphics().guiWidth() - 2, y + 10, 0xA0000000);
            context.graphics().fill(context.graphics().guiWidth() - 3, y - 1,
                context.graphics().guiWidth() - 2, y + 10, accent.value());
            context.graphics().drawString(context.minecraft().font, text, x, y, 0xFFFFFFFF, true);
            y += 11;
        }
    }

}
