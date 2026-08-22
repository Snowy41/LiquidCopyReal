package dev.liquidcopy.client.render;

import net.minecraft.client.gui.GuiGraphics;

public record ScreenBox(int left, int top, int right, int bottom) {
    public ScreenBox {
        if (right < left || bottom < top) {
            throw new IllegalArgumentException("Inverted screen box");
        }
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }

    public boolean visibleIn(int width, int height) {
        return right >= 0 && bottom >= 0 && left < width && top < height;
    }

    public void drawOutline(GuiGraphics graphics, int color, int thickness) {
        int line = Math.max(1, thickness);
        graphics.fill(left, top, right + 1, top + line, color);
        graphics.fill(left, bottom - line + 1, right + 1, bottom + 1, color);
        graphics.fill(left, top, left + line, bottom + 1, color);
        graphics.fill(right - line + 1, top, right + 1, bottom + 1, color);
    }
}
