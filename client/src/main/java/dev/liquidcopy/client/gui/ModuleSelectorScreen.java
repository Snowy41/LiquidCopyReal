package dev.liquidcopy.client.gui;

import dev.liquidcopy.api.module.Module;
import dev.liquidcopy.client.ClientKernel;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Dynamic module selector opened with Right Shift. */
public final class ModuleSelectorScreen extends Screen implements ClickGuiScreen {
    private static final int TOP = 43;
    private static final int BOTTOM = 38;
    private static final int ROW_HEIGHT = 24;
    private final Screen parent;
    private final ClientKernel kernel;

    public ModuleSelectorScreen(Screen parent, ClientKernel kernel) {
        super(Component.literal("LiquidCopy ClickGUI"));
        this.parent = parent;
        this.kernel = kernel;
    }

    @Override
    protected void init() {
        List<Module> modules = kernel.modules().all();
        int rowsPerColumn = Math.max(1, (height - TOP - BOTTOM) / ROW_HEIGHT);
        int columns = Math.max(1, (modules.size() + rowsPerColumn - 1) / rowsPerColumn);
        int gap = 8;
        int availableWidth = Math.max(120, width - 24 - (columns - 1) * gap);
        int columnWidth = Math.clamp(availableWidth / columns, 120, 180);
        int totalWidth = columns * columnWidth + (columns - 1) * gap;
        int startX = (width - totalWidth) / 2;

        for (int index = 0; index < modules.size(); index++) {
            Module module = modules.get(index);
            int column = index / rowsPerColumn;
            int row = index % rowsPerColumn;
            int x = startX + column * (columnWidth + gap);
            int y = TOP + row * ROW_HEIGHT;
            int settingsWidth = 22;
            Button toggle = Button.builder(moduleLabel(module), button -> {
                kernel.toggleModule(module.id(), kernel.context(minecraft));
                button.setMessage(moduleLabel(module));
            }).bounds(x, y, columnWidth - settingsWidth - 3, 20).build();
            addRenderableWidget(toggle);
            addRenderableWidget(Button.builder(Component.literal("..."), button ->
                minecraft.setScreen(new ModuleSettingsScreen(this, kernel, module))
            ).bounds(x + columnWidth - settingsWidth, y, settingsWidth, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(width / 2 - 50, height - 28, 100, 20)
            .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xD010131B);
        graphics.drawCenteredString(font, title, width / 2, 13, 0xFF4DE1FF);
        graphics.drawCenteredString(font,
            "Toggle modules or open ... for settings", width / 2, 26, 0xFFB8C2CC);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        closeClickGui();
    }

    @Override
    public void closeClickGui() {
        kernel.saveConfiguration();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static Component moduleLabel(Module module) {
        return Component.literal((module.isEnabled() ? "[ON] " : "[OFF] ")
            + module.metadata().name());
    }
}
