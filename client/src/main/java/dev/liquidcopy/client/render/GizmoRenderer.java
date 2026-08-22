package dev.liquidcopy.client.render;

import java.util.Collection;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;

public final class GizmoRenderer {
    private GizmoRenderer() {
    }

    public static void entityBoxes(
        Minecraft minecraft,
        Collection<? extends Entity> entities,
        int argb,
        float width,
        boolean alwaysOnTop
    ) {
        if (entities.isEmpty()) {
            return;
        }
        try (Gizmos.TemporaryCollection ignored = minecraft.collectPerTickGizmos()) {
            GizmoStyle style = GizmoStyle.stroke(argb, width);
            for (Entity entity : entities) {
                var properties = Gizmos.cuboid(entity.getBoundingBox(), style).persistForMillis(75);
                if (alwaysOnTop) {
                    properties.setAlwaysOnTop();
                }
            }
        }
    }
}
