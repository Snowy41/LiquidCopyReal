package dev.liquidcopy.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScreenBoxTest {
    @Test
    void computesDimensionsAndVisibility() {
        ScreenBox box = new ScreenBox(10, 20, 30, 55);
        assertEquals(20, box.width());
        assertEquals(35, box.height());
        assertTrue(box.visibleIn(100, 100));
        assertFalse(new ScreenBox(101, 20, 120, 40).visibleIn(100, 100));
    }

    @Test
    void rejectsInvertedCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new ScreenBox(5, 5, 4, 6));
    }
}
