package dev.liquidcopy.client.service;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.entity.Entity;

/** Coordinates overlapping outline modules and restores each entity's original glowing tag. */
public final class GlowController {
    private final Map<Entity, Lease> leases = new IdentityHashMap<>();
    private final Map<Object, Set<Entity>> targetsByOwner = new IdentityHashMap<>();

    public void update(Object owner, Collection<? extends Entity> requestedTargets) {
        Set<Entity> requested = identitySet();
        requested.addAll(requestedTargets);
        Set<Entity> previous = targetsByOwner.computeIfAbsent(owner, ignored -> identitySet());

        for (Entity entity : Set.copyOf(previous)) {
            if (!requested.contains(entity)) {
                release(owner, entity);
                previous.remove(entity);
            }
        }
        for (Entity entity : requested) {
            if (previous.add(entity)) {
                acquire(owner, entity);
            } else {
                entity.setGlowingTag(true);
            }
        }
    }

    public void clear(Object owner) {
        Set<Entity> previous = targetsByOwner.remove(owner);
        if (previous == null) {
            return;
        }
        for (Entity entity : previous) {
            release(owner, entity);
        }
    }

    public void clearAll() {
        for (Map.Entry<Entity, Lease> entry : leases.entrySet()) {
            entry.getKey().setGlowingTag(entry.getValue().originalGlowingTag);
        }
        leases.clear();
        targetsByOwner.clear();
    }

    public int leasedEntityCount() {
        return leases.size();
    }

    private void acquire(Object owner, Entity entity) {
        Lease lease = leases.computeIfAbsent(entity, target -> new Lease(target.hasGlowingTag()));
        lease.owners.add(owner);
        entity.setGlowingTag(true);
    }

    private void release(Object owner, Entity entity) {
        Lease lease = leases.get(entity);
        if (lease == null || !lease.owners.remove(owner)) {
            return;
        }
        if (lease.owners.isEmpty()) {
            entity.setGlowingTag(lease.originalGlowingTag);
            leases.remove(entity);
        }
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static final class Lease {
        private final boolean originalGlowingTag;
        private final Set<Object> owners = identitySet();

        private Lease(boolean originalGlowingTag) {
            this.originalGlowingTag = originalGlowingTag;
        }
    }
}
