package dev.liquidcopy.client.module;

import dev.liquidcopy.api.module.ModuleCategory;
import dev.liquidcopy.api.module.ModuleContext;
import dev.liquidcopy.api.module.ModuleMetadata;
import dev.liquidcopy.api.setting.BooleanSetting;
import dev.liquidcopy.api.setting.KeybindSetting;
import dev.liquidcopy.api.setting.NumberSetting;
import dev.liquidcopy.client.ClientKernel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

public final class TriggerbotModule extends MinecraftModule {
    private final NumberSetting cooldown = setting(new NumberSetting("cooldown", "Cooldown", 0.92D, 0.1D, 1.0D, 0.01D));
    private final NumberSetting delay = setting(new NumberSetting("delay", "Delay Ticks", 0.0D, 0.0D, 10.0D, 1.0D));
    private final NumberSetting range = setting(new NumberSetting("range", "Maximum Range", 4.5D, 2.0D, 6.0D, 0.1D));
    private final BooleanSetting playersOnly = setting(new BooleanSetting("players_only", "Players Only", true));
    private final BooleanSetting ignoreInvisible = setting(new BooleanSetting("ignore_invisible", "Ignore Invisible", false));
    private long nextAttackTick;
    private Method startAttack;

    public TriggerbotModule() {
        super(new ModuleMetadata(
            "triggerbot", "Triggerbot", "Attacks a valid crosshair target when the shared cooldown is ready.",
            ModuleCategory.COMBAT, false
        ), KeybindSetting.UNBOUND);
    }

    @Override
    protected void onTick(ModuleContext context) {
        Minecraft minecraft = minecraft(context);
        if (context.tick() < nextAttackTick || minecraft.screen != null || minecraft.player == null
            || minecraft.gameMode == null || !(minecraft.hitResult instanceof EntityHitResult hit)) {
            return;
        }
        Entity target = hit.getEntity();
        if (!validTarget(minecraft, target)) {
            return;
        }
        CooldownSyncModule sync = context.require(ClientKernel.class).modules().find("cooldown_sync")
            .filter(CooldownSyncModule.class::isInstance)
            .map(CooldownSyncModule.class::cast)
            .orElse(null);
        boolean ready = sync != null
            ? sync.ready(minecraft.player, cooldown.value())
            : minecraft.player.getAttackStrengthScale(0.5F) >= cooldown.value();
        if (!ready) {
            return;
        }
        invokeVanillaAttack(minecraft);
        nextAttackTick = context.tick() + delay.value().longValue() + 1L;
    }

    @Override
    protected void onDisable(ModuleContext context) {
        nextAttackTick = 0L;
    }

    private boolean validTarget(Minecraft minecraft, Entity target) {
        if (target == minecraft.player || !target.isAlive() || target.isSpectator()) {
            return false;
        }
        if (playersOnly.value() && !(target instanceof Player)) {
            return false;
        }
        if (ignoreInvisible.value() && target.isInvisible()) {
            return false;
        }
        return minecraft.player.distanceToSqr(target) <= range.value() * range.value();
    }

    private void invokeVanillaAttack(Minecraft minecraft) {
        try {
            startAttackMethod().invoke(minecraft);
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new IllegalStateException("Unable to invoke Minecraft.startAttack", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Minecraft.startAttack failed", cause);
        }
    }

    private Method startAttackMethod() throws NoSuchMethodException {
        if (startAttack == null) {
            Method resolved = Minecraft.class.getDeclaredMethod("startAttack");
            resolved.setAccessible(true);
            startAttack = resolved;
        }
        return startAttack;
    }
}
