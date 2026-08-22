package dev.liquidcopy.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class EventBusTest {
    private record TestEvent(String value) implements ClientEvent {
    }

    @Test
    void dispatchesInPriorityOrder() {
        EventBus bus = new EventBus();
        List<String> calls = new ArrayList<>();
        bus.subscribe(TestEvent.class, 0, event -> calls.add("normal:" + event.value()));
        bus.subscribe(TestEvent.class, 10, event -> calls.add("high:" + event.value()));

        bus.post(new TestEvent("tick"));

        assertEquals(List.of("high:tick", "normal:tick"), calls);
    }
}

