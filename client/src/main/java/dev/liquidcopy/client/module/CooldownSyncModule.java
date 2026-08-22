package dev.liquidcopy.client.module;

import dev.liquidcopy.api.module.ModuleCategory;
import dev.liquidcopy.api.module.ModuleContext;
import dev.liquidcopy.api.module.ModuleMetadata;
import dev.liquidcopy.api.setting.BooleanSetting;
import dev.liquidcopy.api.setting.ColorSetting;
import dev.liquidcopy.api.setting.NumberSetting;
import dev.liquidcopy.client.render.HudRenderContext;
import dev.liquidcopy.client.render.HudRenderable;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import dev.liquidcopy.api.setting.KeybindSetting;

public final class CooldownSyncModule extends MinecraftModule implements HudRenderable {
    private final NumberSetting threshold = setting(new NumberSetting("threshold", "Ready Threshold", 0.92D, 0.1D, 1.0D, 0.01D));
    private final BooleanSetting compensatePartialTick = setting(new BooleanSetting("partial_tick", "Partial Tick Compensation", true));
    private final BooleanSetting showBar = setting(new BooleanSetting("show_bar", "Show Bar", true));
    private final ColorSetting readyColor = setting(new ColorSetting("ready_color", "Ready Color", 0xFF55FF88));
    private volatile float strength;

    public CooldownSyncModule() {
        super(new ModuleMetadata(
            "cooldown_sync", "Cooldown Sync", "Shares one attack-cooldown gate with combat modules.",
            ModuleCategory.COMBAT, false
        ), KeybindSetting.UNBOUND);
    }

    @Override
    protected void onTick(ModuleContext context) {
        Minecraft minecraft = minecraft(context);
        strength = minecraft.player == null ? 0.0F : currentStrength(minecraft.player);
    }

    @Override
    protected void onDisable(ModuleContext context) {
        strength = 0.0F;
    }

    public boolean ready(Player player, double fallbackThreshold) {
        double required = isEnabled() ? threshold.value() : fallbackThreshold;
        return currentStrength(player) >= required;
    }

    public float strength() {
        return strength;
    }

    @Override
    public void renderHud(HudRenderContext context) {
        if (!showBar.value() || context.minecraft().player == null) {
            return;
        }
        int center = context.graphics().guiWidth() / 2;
        int y = context.graphics().guiHeight() / 2 + 14;
        int width = 42;
        int filled = Math.round(Math.clamp(strength, 0.0F, 1.0F) * width);
        context.graphics().fill(center - width / 2 - 1, y - 1, center + width / 2 + 1, y + 4, 0xAA000000);
        int color = strength >= threshold.value() ? readyColor.value() : 0xFFFFAA44;
        context.graphics().fill(center - width / 2, y, center - width / 2 + filled, y + 3, color);
    }

    private float currentStrength(Player player) {
        return player.getAttackStrengthScale(compensatePartialTick.value() ? 0.5F : 0.0F);
    }
}
