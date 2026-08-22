package dev.liquidcopy.client.module;

import dev.liquidcopy.api.module.ModuleCategory;
import dev.liquidcopy.api.module.ModuleContext;
import dev.liquidcopy.api.module.ModuleMetadata;
import dev.liquidcopy.api.setting.EnumSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import dev.liquidcopy.api.setting.KeybindSetting;

public final class SprintModule extends MinecraftModule {
    private final EnumSetting<Mode> mode = setting(new EnumSetting<>("mode", "Mode", Mode.class, Mode.LEGIT));
    private LocalPlayer forcedPlayer;
    private boolean forcedByModule;

    public SprintModule() {
        super(new ModuleMetadata(
            "sprint", "Sprint", "Automatically keeps the local player sprinting while moving.",
            ModuleCategory.MOVEMENT, false
        ), KeybindSetting.UNBOUND);
    }

    @Override
    protected void onTick(ModuleContext context) {
        Minecraft minecraft = minecraft(context);
        LocalPlayer player = minecraft.player;
        if (player == null || player.input == null || player.isSpectator() || player.isMovingSlowly()) {
            return;
        }
        boolean moving = mode.value() == Mode.LEGIT
            ? player.input.hasForwardImpulse()
            : player.input.getMoveVector().lengthSquared() > 0.01F;
        if (!moving) {
            return;
        }
        if (!player.isSprinting()) {
            forcedByModule = true;
        }
        forcedPlayer = player;
        player.setSprinting(true);
    }

    @Override
    protected void onDisable(ModuleContext context) {
        if (forcedByModule && forcedPlayer != null && forcedPlayer.isAlive()) {
            forcedPlayer.setSprinting(false);
        }
        forcedPlayer = null;
        forcedByModule = false;
    }

    public enum Mode {
        LEGIT,
        OMNIDIRECTIONAL
    }
}
