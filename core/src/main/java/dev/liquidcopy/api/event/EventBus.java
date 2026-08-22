package dev.liquidcopy.api.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Small synchronous event bus. Listeners are snapshot-safe and ordered by
 * descending priority, then registration order.
 */
public final class EventBus {
    private final CopyOnWriteArrayList<Subscription<?>> subscriptions = new CopyOnWriteArrayList<>();

    public <E extends ClientEvent> AutoCloseable subscribe(
        Class<E> eventType,
        int priority,
        Consumer<? super E> listener
    ) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
        Subscription<E> subscription = new Subscription<>(eventType, priority, listener);
        subscriptions.add(subscription);
        subscriptions.sort(Comparator.comparingInt(Subscription<?>::priority).reversed());
        return () -> subscriptions.remove(subscription);
    }

    public <E extends ClientEvent> E post(E event) {
        Objects.requireNonNull(event, "event");
        for (Subscription<?> raw : List.copyOf(subscriptions)) {
            raw.dispatch(event);
        }
        return event;
    }

    public int listenerCount() {
        return subscriptions.size();
    }

    public List<String> listenerTypes() {
        List<String> names = new ArrayList<>();
        for (Subscription<?> subscription : subscriptions) {
            names.add(subscription.eventType().getName());
        }
        return List.copyOf(names);
    }

    private record Subscription<E extends ClientEvent>(
        Class<E> eventType,
        int priority,
        Consumer<? super E> listener
    ) {
        private void dispatch(ClientEvent event) {
            if (eventType.isInstance(event)) {
                listener.accept(eventType.cast(event));
            }
        }
    }
}

