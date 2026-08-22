package dev.liquidcopy.client.render;

import java.util.OptionalInt;
import net.minecraft.world.entity.Entity;

public interface EntityOutlineProvider {
    OptionalInt outlineColor(Entity entity);
}
