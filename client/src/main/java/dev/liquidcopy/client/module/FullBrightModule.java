package dev.liquidcopy.client.module;

import dev.liquidcopy.api.module.ModuleCategory;
import dev.liquidcopy.api.module.ModuleContext;
import dev.liquidcopy.api.module.ModuleMetadata;
import dev.liquidcopy.api.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import dev.liquidcopy.api.setting.KeybindSetting;

public final class FullBrightModule extends MinecraftModule {
    private final NumberSetting brightness = setting(new NumberSetting("brightness", "Gamma", 1.0D, 0.0D, 1.0D, 0.05D));
    private OptionInstance<Double> gamma;
    private Double previousGamma;

    public FullBrightModule() {
        super(new ModuleMetadata(
            "fullbright", "FullBright", "Pins client gamma to the selected brightness and restores it on disable.",
            ModuleCategory.RENDER, false
        ), KeybindSetting.UNBOUND);
    }

    @Override
    protected void onEnable(ModuleContext context) {
        captureAndApply(minecraft(context));
    }

    @Override
    protected void onTick(ModuleContext context) {
        Minecraft minecraft = minecraft(context);
        if (gamma != minecraft.options.gamma()) {
            restore();
            captureAndApply(minecraft);
        } else if (!brightness.value().equals(gamma.get())) {
            gamma.set(brightness.value());
        }
    }

    @Override
    protected void onDisable(ModuleContext context) {
        restore();
    }

    private void captureAndApply(Minecraft minecraft) {
        gamma = minecraft.options.gamma();
        previousGamma = gamma.get();
        gamma.set(brightness.value());
    }

    private void restore() {
        if (gamma != null && previousGamma != null) {
            gamma.set(previousGamma);
        }
        gamma = null;
        previousGamma = null;
    }
}
