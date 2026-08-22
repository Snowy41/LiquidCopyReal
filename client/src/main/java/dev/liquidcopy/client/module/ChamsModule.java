package dev.liquidcopy.client.module;

import dev.liquidcopy.api.module.ModuleCategory;
import dev.liquidcopy.api.module.ModuleContext;
import dev.liquidcopy.api.module.ModuleMetadata;
import dev.liquidcopy.api.setting.BooleanSetting;
import dev.liquidcopy.api.setting.ColorSetting;
import dev.liquidcopy.api.setting.NumberSetting;
import dev.liquidcopy.client.render.EntityOutlineProvider;
import dev.liquidcopy.client.service.GlowController;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import dev.liquidcopy.api.setting.KeybindSetting;

public final class ChamsModule extends MinecraftModule implements EntityOutlineProvider {
    private final ColorSetting color = setting(new ColorSetting("color", "Color", 0xFFAA66FF));
    private final NumberSetting range = setting(new NumberSetting("range", "Range", 96.0D, 8.0D, 192.0D, 8.0D));
    private final BooleanSetting playersOnly = setting(new BooleanSetting("players_only", "Players Only", true));
    private final BooleanSetting includeInvisible = setting(new BooleanSetting("include_invisible", "Include Invisible", true));
    private volatile List<LivingEntity> targets = List.of();

    public ChamsModule() {
        super(new ModuleMetadata(
            "chams", "Chams", "Renders wall-visible colored silhouettes for living targets.",
            ModuleCategory.RENDER, false
        ), KeybindSetting.UNBOUND);
    }

    @Override
    protected void onTick(ModuleContext context) {
        Minecraft minecraft = minecraft(context);
        if (minecraft.level == null || minecraft.player == null) {
            targets = List.of();
            context.require(GlowController.class).clear(this);
            return;
        }
        double maximumDistanceSq = range.value() * range.value();
        List<LivingEntity> selected = new ArrayList<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living == minecraft.player || !living.isAlive()) {
                continue;
            }
            if (playersOnly.value() && !(living instanceof Player)) {
                continue;
            }
            if (!includeInvisible.value() && living.isInvisible()) {
                continue;
            }
            if (minecraft.player.distanceToSqr(living) <= maximumDistanceSq) {
                selected.add(living);
            }
        }
        targets = List.copyOf(selected);
        context.require(GlowController.class).update(this, selected);
    }

    @Override
    protected void onDisable(ModuleContext context) {
        targets = List.of();
        context.service(GlowController.class).ifPresent(controller -> controller.clear(this));
    }

    @Override
    public OptionalInt outlineColor(Entity entity) {
        return targets.contains(entity) ? OptionalInt.of(color.value()) : OptionalInt.empty();
    }
}
