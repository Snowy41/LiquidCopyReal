package dev.liquidcopy.client.module;

import dev.liquidcopy.api.module.ModuleCategory;
import dev.liquidcopy.api.module.ModuleContext;
import dev.liquidcopy.api.module.ModuleMetadata;
import dev.liquidcopy.api.setting.BooleanSetting;
import dev.liquidcopy.api.setting.ColorSetting;
import dev.liquidcopy.api.setting.NumberSetting;
import dev.liquidcopy.client.render.HudRenderContext;
import dev.liquidcopy.client.render.HudRenderable;
import dev.liquidcopy.client.render.EntityRenderStateMutator;
import dev.liquidcopy.client.render.ScreenProjector;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import dev.liquidcopy.api.setting.KeybindSetting;

public final class NametagsModule extends MinecraftModule implements HudRenderable, EntityRenderStateMutator {
    private final ColorSetting color = setting(new ColorSetting("color", "Color", 0xFFFFFFFF));
    private final NumberSetting range = setting(new NumberSetting("range", "Range", 96.0D, 8.0D, 192.0D, 8.0D));
    private final BooleanSetting showHealth = setting(new BooleanSetting("show_health", "Show Health", true));
    private final BooleanSetting showDistance = setting(new BooleanSetting("show_distance", "Show Distance", true));
    private final BooleanSetting background = setting(new BooleanSetting("background", "Background", true));
    private volatile List<Player> targets = List.of();

    public NametagsModule() {
        super(new ModuleMetadata(
            "nametags", "Nametags", "Draws health and distance tags above nearby players.",
            ModuleCategory.RENDER, false
        ), KeybindSetting.UNBOUND);
    }

    @Override
    protected void onTick(ModuleContext context) {
        Minecraft minecraft = minecraft(context);
        if (minecraft.level == null || minecraft.player == null) {
            targets = List.of();
            return;
        }
        double maximumDistanceSq = range.value() * range.value();
        List<Player> selected = new ArrayList<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof Player player && player != minecraft.player && player.isAlive()
                && !player.isSpectator() && minecraft.player.distanceToSqr(player) <= maximumDistanceSq) {
                selected.add(player);
            }
        }
        targets = List.copyOf(selected);
    }

    @Override
    protected void onDisable(ModuleContext context) {
        targets = List.of();
    }

    @Override
    public void mutateRenderState(Entity entity, EntityRenderState state) {
        if (targets.contains(entity)) {
            // This module draws the complete health/distance tag in HUD space. Suppress the
            // vanilla tag in this ephemeral render state to avoid a duplicate label.
            state.nameTag = null;
        }
    }

    @Override
    public void renderHud(HudRenderContext context) {
        if (context.minecraft().player == null) {
            return;
        }
        int screenWidth = context.graphics().guiWidth();
        int screenHeight = context.graphics().guiHeight();
        for (Player player : targets) {
            ScreenProjector.project(context.minecraft(), player.getBoundingBox(), screenWidth, screenHeight)
                .ifPresent(box -> renderTag(context, player, box.left() + box.width() / 2, box.top() - 12));
        }
    }

    private void renderTag(HudRenderContext context, Player player, int centerX, int y) {
        StringBuilder label = new StringBuilder(player.getDisplayName().getString());
        if (showHealth.value()) {
            label.append(String.format(Locale.ROOT, " %.1f♥", player.getHealth()));
        }
        if (showDistance.value()) {
            double distance = Math.sqrt(context.minecraft().player.distanceToSqr(player));
            label.append(String.format(Locale.ROOT, " %.0fm", distance));
        }
        String text = label.toString();
        int width = context.minecraft().font.width(text);
        int x = centerX - width / 2;
        if (background.value()) {
            context.graphics().fill(x - 3, y - 2, x + width + 3, y + 10, 0xB0000000);
        }
        context.graphics().drawString(context.minecraft().font, text, x, y, color.value(), true);
    }
}
