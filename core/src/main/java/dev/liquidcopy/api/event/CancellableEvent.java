package dev.liquidcopy.api.event;

public abstract class CancellableEvent implements ClientEvent {
    private boolean cancelled;

    public final boolean isCancelled() {
        return cancelled;
    }

    public final void cancel() {
        cancelled = true;
    }
}

