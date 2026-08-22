package dev.liquidcopy.client.render;

import java.util.Optional;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

public final class ScreenProjector {
    private ScreenProjector() {
    }

    public static Optional<ScreenBox> project(Minecraft minecraft, AABB box, int width, int height) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPosition = camera.position();
        Vector3fc forward = camera.forwardVector();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        boolean anyInFront = false;

        for (int corner = 0; corner < 8; corner++) {
            Vec3 point = new Vec3(
                (corner & 1) == 0 ? box.minX : box.maxX,
                (corner & 2) == 0 ? box.minY : box.maxY,
                (corner & 4) == 0 ? box.minZ : box.maxZ
            );
            Vec3 relative = point.subtract(cameraPosition);
            double facing = relative.x * forward.x() + relative.y * forward.y() + relative.z * forward.z();
            if (facing <= 0.01D) {
                continue;
            }
            anyInFront = true;
            Vec3 projected = minecraft.gameRenderer.projectPointToScreen(point);
            if (!Double.isFinite(projected.x) || !Double.isFinite(projected.y)) {
                continue;
            }
            double screenX = (projected.x * 0.5D + 0.5D) * width;
            double screenY = (0.5D - projected.y * 0.5D) * height;
            minX = Math.min(minX, screenX);
            minY = Math.min(minY, screenY);
            maxX = Math.max(maxX, screenX);
            maxY = Math.max(maxY, screenY);
        }

        if (!anyInFront || !Double.isFinite(minX) || !Double.isFinite(minY)) {
            return Optional.empty();
        }
        ScreenBox result = new ScreenBox(
            (int) Math.floor(minX),
            (int) Math.floor(minY),
            (int) Math.ceil(maxX),
            (int) Math.ceil(maxY)
        );
        return result.visibleIn(width, height) ? Optional.of(result) : Optional.empty();
    }
}
