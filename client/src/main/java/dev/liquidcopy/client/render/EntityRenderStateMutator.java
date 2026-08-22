package dev.liquidcopy.client.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

/** Optional module hook for changes scoped to a freshly extracted per-frame render state. */
public interface EntityRenderStateMutator {
    void mutateRenderState(Entity entity, EntityRenderState state);
}
