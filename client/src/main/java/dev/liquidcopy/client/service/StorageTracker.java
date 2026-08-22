package dev.liquidcopy.client.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Receives optional renderer extraction callbacks and retains only recently visible storage. */
public final class StorageTracker {
    private final Map<BlockPos, Target> targets = new ConcurrentHashMap<>();

    public void observe(Object candidate, long tick) {
        if (!(candidate instanceof BlockEntity blockEntity)) {
            return;
        }
        String label = StorageTypes.label(blockEntity);
        if (label == null) {
            return;
        }
        BlockPos position = blockEntity.getBlockPos().immutable();
        targets.put(position, new Target(position, label, tick));
    }

    public void observe(BlockEntity blockEntity, long tick) {
        observe((Object) blockEntity, tick);
    }

    public List<Target> recent(long tick, long maximumAge) {
        return targets.values().stream()
            .filter(target -> tick - target.lastSeenTick() <= maximumAge)
            .sorted(Comparator.comparing(Target::label)
                .thenComparingInt(target -> target.position().getX())
                .thenComparingInt(target -> target.position().getZ()))
            .toList();
    }

    public void prune(long tick, long maximumAge) {
        targets.values().removeIf(target -> tick - target.lastSeenTick() > maximumAge);
    }

    public void clear() {
        targets.clear();
    }

    public record Target(BlockPos position, String label, long lastSeenTick) {
    }
}
