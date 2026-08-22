package dev.liquidcopy.client.module;

import dev.liquidcopy.api.module.ModuleCategory;
import dev.liquidcopy.api.module.ModuleContext;
import dev.liquidcopy.api.module.ModuleMetadata;
import dev.liquidcopy.api.setting.BooleanSetting;
import dev.liquidcopy.api.setting.ColorSetting;
import dev.liquidcopy.api.setting.NumberSetting;
import dev.liquidcopy.client.render.EntityOutlineProvider;
import dev.liquidcopy.client.render.GizmoRenderer;
import dev.liquidcopy.client.render.HudRenderContext;
import dev.liquidcopy.client.render.HudRenderable;
import dev.liquidcopy.client.render.ScreenProjector;
import dev.liquidcopy.client.service.GlowController;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import dev.liquidcopy.api.setting.KeybindSetting;

public final class ItemEspModule extends MinecraftModule implements HudRenderable, EntityOutlineProvider {
    private final ColorSetting color = setting(new ColorSetting("color", "Color", 0xFFFFD54F));
    private final NumberSetting range = setting(new NumberSetting("range", "Range", 96.0D, 8.0D, 192.0D, 8.0D));
    private final BooleanSetting throughWalls = setting(new BooleanSetting("through_walls", "Through Walls", true));
    private volatile List<ItemEntity> targets = List.of();

    public ItemEspModule() {
        super(new ModuleMetadata(
            "item_esp", "Item ESP", "Highlights dropped items through terrain.",
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
        List<ItemEntity> selected = new ArrayList<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof ItemEntity item && item.isAlive()
                && minecraft.player.distanceToSqr(item) <= maximumDistanceSq) {
                selected.add(item);
            }
        }
        targets = List.copyOf(selected);
        GlowController glowController = context.require(GlowController.class);
        if (throughWalls.value()) {
            glowController.update(this, selected);
        } else {
            glowController.clear(this);
        }
        GizmoRenderer.entityBoxes(minecraft, selected, color.value(), 1.0F, throughWalls.value());
    }

    @Override
    protected void onDisable(ModuleContext context) {
        targets = List.of();
        context.service(GlowController.class).ifPresent(controller -> controller.clear(this));
    }

    @Override
    public void renderHud(HudRenderContext context) {
        if (!throughWalls.value()) {
            return;
        }
        int width = context.graphics().guiWidth();
        int height = context.graphics().guiHeight();
        for (ItemEntity item : targets) {
            ScreenProjector.project(context.minecraft(), item.getBoundingBox(), width, height)
                .ifPresent(box -> box.drawOutline(context.graphics(), color.value(), 1));
        }
    }

    @Override
    public OptionalInt outlineColor(Entity entity) {
        return throughWalls.value() && targets.contains(entity)
            ? OptionalInt.of(color.value()) : OptionalInt.empty();
    }
}
