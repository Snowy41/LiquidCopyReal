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
import net.minecraft.world.entity.player.Player;
import dev.liquidcopy.api.setting.KeybindSetting;

public final class PlayerEspModule extends MinecraftModule
    implements HudRenderable, EntityOutlineProvider {
    private final ColorSetting color = setting(new ColorSetting("color", "Color", 0xFFFF4D4D));
    private final NumberSetting range = setting(new NumberSetting("range", "Range", 128.0D, 8.0D, 256.0D, 8.0D));
    private final NumberSetting lineWidth = setting(new NumberSetting("line_width", "Line Width", 1.5D, 0.5D, 5.0D, 0.5D));
    private final BooleanSetting includeSelf = setting(new BooleanSetting("include_self", "Include Self", false));
    private final BooleanSetting throughWalls = setting(new BooleanSetting("through_walls", "Through Walls", true));
    private volatile List<Player> targets = List.of();

    public PlayerEspModule() {
        super(new ModuleMetadata(
            "player_esp", "Player ESP", "Highlights players with colored outlines and 2D boxes.",
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
        List<Player> selected = new ArrayList<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof Player player) || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (!includeSelf.value() && player == minecraft.player) {
                continue;
            }
            if (minecraft.player.distanceToSqr(player) <= maximumDistanceSq) {
                selected.add(player);
            }
        }
        targets = List.copyOf(selected);
        GlowController glowController = context.require(GlowController.class);
        if (throughWalls.value()) {
            glowController.update(this, selected);
        } else {
            glowController.clear(this);
        }
        GizmoRenderer.entityBoxes(
            minecraft, selected, color.value(), lineWidth.value().floatValue(), throughWalls.value()
        );
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
        for (Player player : targets) {
            ScreenProjector.project(context.minecraft(), player.getBoundingBox(), width, height)
                .ifPresent(box -> box.drawOutline(context.graphics(), color.value(), 1));
        }
    }

    @Override
    public OptionalInt outlineColor(Entity entity) {
        return throughWalls.value() && targets.contains(entity)
            ? OptionalInt.of(color.value()) : OptionalInt.empty();
    }

    public int targetCount() {
        return targets.size();
    }
}
