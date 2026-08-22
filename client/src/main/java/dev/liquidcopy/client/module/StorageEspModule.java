package dev.liquidcopy.client.module;

import dev.liquidcopy.api.module.ModuleCategory;
import dev.liquidcopy.api.module.ModuleContext;
import dev.liquidcopy.api.module.ModuleMetadata;
import dev.liquidcopy.api.setting.BooleanSetting;
import dev.liquidcopy.api.setting.ColorSetting;
import dev.liquidcopy.api.setting.NumberSetting;
import dev.liquidcopy.client.render.HudRenderContext;
import dev.liquidcopy.client.render.HudRenderable;
import dev.liquidcopy.client.render.ScreenProjector;
import dev.liquidcopy.client.service.StorageTracker;
import dev.liquidcopy.client.service.StorageTypes;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import dev.liquidcopy.api.setting.KeybindSetting;

public final class StorageEspModule extends MinecraftModule implements HudRenderable {
    private final ColorSetting color = setting(new ColorSetting("color", "Color", 0xFF55E6FF));
    private final NumberSetting range = setting(new NumberSetting("range", "Range", 96.0D, 16.0D, 160.0D, 16.0D));
    private final NumberSetting scanInterval = setting(new NumberSetting("scan_interval", "Scan Interval", 10.0D, 1.0D, 40.0D, 1.0D));
    private final NumberSetting maxLabels = setting(new NumberSetting("max_labels", "Max Labels", 48.0D, 4.0D, 128.0D, 4.0D));
    private final BooleanSetting throughWalls = setting(new BooleanSetting("through_walls", "Through Walls", true));
    private volatile List<StorageTracker.Target> targets = List.of();

    public StorageEspModule() {
        super(new ModuleMetadata(
            "storage_esp", "Storage ESP", "Marks nearby storage with world boxes and screen labels.",
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
        StorageTracker tracker = context.require(StorageTracker.class);
        if (context.tick() % scanInterval.value().longValue() == 0L) {
            scanLoadedChunks(minecraft, tracker, context.tick());
        }
        double rangeSq = range.value() * range.value();
        BlockPos origin = minecraft.player.blockPosition();
        targets = tracker.recent(context.tick(), Math.max(20L, scanInterval.value().longValue() * 3L)).stream()
            .filter(target -> distanceSquared(origin, target.position()) <= rangeSq)
            .sorted(Comparator.comparingDouble(target -> distanceSquared(origin, target.position())))
            .limit(maxLabels.value().longValue())
            .toList();
        submitGizmos(minecraft, targets);
    }

    @Override
    protected void onDisable(ModuleContext context) {
        targets = List.of();
    }

    @Override
    public void renderHud(HudRenderContext context) {
        if (!throughWalls.value()) {
            return;
        }
        int width = context.graphics().guiWidth();
        int height = context.graphics().guiHeight();
        for (StorageTracker.Target target : targets) {
            ScreenProjector.project(context.minecraft(), new AABB(target.position()), width, height)
                .ifPresent(box -> {
                    box.drawOutline(context.graphics(), color.value(), 1);
                    int textWidth = context.minecraft().font.width(target.label());
                    int textX = box.left() + (box.width() - textWidth) / 2;
                    int textY = box.top() - 10;
                    context.graphics().fill(textX - 2, textY - 1, textX + textWidth + 2, textY + 9, 0x99000000);
                    context.graphics().drawString(context.minecraft().font, target.label(), textX, textY, color.value(), true);
                });
        }
    }

    private void scanLoadedChunks(Minecraft minecraft, StorageTracker tracker, long tick) {
        int centerX = minecraft.player.blockPosition().getX() >> 4;
        int centerZ = minecraft.player.blockPosition().getZ() >> 4;
        int chunkRadius = Math.max(1, (int) Math.ceil(range.value() / 16.0D));
        for (int chunkX = centerX - chunkRadius; chunkX <= centerX + chunkRadius; chunkX++) {
            for (int chunkZ = centerZ - chunkRadius; chunkZ <= centerZ + chunkRadius; chunkZ++) {
                LevelChunk chunk = minecraft.level.getChunkSource()
                    .getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (StorageTypes.isStorage(blockEntity)) {
                        tracker.observe(blockEntity, tick);
                    }
                }
            }
        }
    }

    private void submitGizmos(Minecraft minecraft, List<StorageTracker.Target> selected) {
        if (selected.isEmpty()) {
            return;
        }
        try (Gizmos.TemporaryCollection ignored = minecraft.collectPerTickGizmos()) {
            GizmoStyle style = GizmoStyle.stroke(color.value(), 1.5F);
            for (StorageTracker.Target target : selected) {
                var properties = Gizmos.cuboid(target.position(), style).persistForMillis(75);
                if (throughWalls.value()) {
                    properties.setAlwaysOnTop();
                }
            }
        }
    }

    private static double distanceSquared(BlockPos first, BlockPos second) {
        double x = first.getX() - second.getX();
        double y = first.getY() - second.getY();
        double z = first.getZ() - second.getZ();
        return x * x + y * y + z * z;
    }
}
