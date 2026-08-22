package dev.liquidcopy.client.module;

import dev.liquidcopy.api.module.ModuleCategory;
import dev.liquidcopy.api.module.ModuleContext;
import dev.liquidcopy.api.module.ModuleMetadata;
import dev.liquidcopy.api.setting.BooleanSetting;
import dev.liquidcopy.api.setting.KeybindSetting;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;

public final class NoJumpDelayModule extends MinecraftModule {
    private final BooleanSetting onlyOnGround = setting(new BooleanSetting("only_on_ground", "Only On Ground", false));
    private Field noJumpDelay;
    private LocalPlayer modifiedPlayer;
    private int originalVanillaValue;
    private boolean originalValueCaptured;

    public NoJumpDelayModule() {
        super(new ModuleMetadata(
            "no_jump_delay", "No Jump Delay", "Clears LivingEntity's ten-tick ground-jump delay.",
            ModuleCategory.MOVEMENT, false
        ), KeybindSetting.UNBOUND);
    }

    @Override
    protected void onTick(ModuleContext context) {
        Minecraft minecraft = minecraft(context);
        LocalPlayer player = minecraft.player;
        if (player == null) {
            restoreOriginalValue();
            return;
        }
        try {
            Field field = noJumpDelayField();
            if (modifiedPlayer != player) {
                restoreOriginalValue();
                modifiedPlayer = player;
            }
            if (onlyOnGround.value() && !player.onGround()) {
                // No write happened this tick, so an older transient countdown must not be
                // reintroduced later when the module is disabled.
                originalValueCaptured = false;
                return;
            }
            // Refresh on every write. A one-time snapshot becomes stale as vanilla's transient
            // countdown naturally expires and would invent a new delay on a much later disable.
            originalVanillaValue = field.getInt(player);
            originalValueCaptured = true;
            field.setInt(player, 0);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to update LivingEntity.noJumpDelay", failure);
        }
    }

    @Override
    protected void onDisable(ModuleContext context) {
        restoreOriginalValue();
    }

    private Field noJumpDelayField() throws NoSuchFieldException {
        if (noJumpDelay == null) {
            Field resolved = LivingEntity.class.getDeclaredField("noJumpDelay");
            resolved.setAccessible(true);
            noJumpDelay = resolved;
        }
        return noJumpDelay;
    }

    private void restoreOriginalValue() {
        if (modifiedPlayer != null && noJumpDelay != null && originalValueCaptured) {
            try {
                noJumpDelay.setInt(modifiedPlayer, originalVanillaValue);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Unable to restore LivingEntity.noJumpDelay", failure);
            }
        }
        modifiedPlayer = null;
        originalVanillaValue = 0;
        originalValueCaptured = false;
    }
}
