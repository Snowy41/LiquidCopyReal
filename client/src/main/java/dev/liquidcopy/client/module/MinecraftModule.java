package dev.liquidcopy.client.module;

import dev.liquidcopy.api.module.Module;
import dev.liquidcopy.api.module.ModuleContext;
import dev.liquidcopy.api.module.ModuleMetadata;
import net.minecraft.client.Minecraft;

abstract class MinecraftModule extends Module {
    protected MinecraftModule(ModuleMetadata metadata, int defaultKeyCode) {
        super(metadata, defaultKeyCode);
    }

    protected final Minecraft minecraft(ModuleContext context) {
        return context.service(Minecraft.class).orElseGet(Minecraft::getInstance);
    }
}
