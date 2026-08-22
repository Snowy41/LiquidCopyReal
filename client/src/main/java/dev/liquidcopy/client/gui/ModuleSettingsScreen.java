package dev.liquidcopy.client.gui;

import dev.liquidcopy.api.module.Module;
import dev.liquidcopy.api.setting.BooleanSetting;
import dev.liquidcopy.api.setting.ColorSetting;
import dev.liquidcopy.api.setting.EnumSetting;
import dev.liquidcopy.api.setting.KeybindSetting;
import dev.liquidcopy.api.setting.NumberSetting;
import dev.liquidcopy.api.setting.Setting;
import dev.liquidcopy.client.ClientKernel;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Per-module typed setting editor used by the ClickGUI. */
final class ModuleSettingsScreen extends Screen implements ClickGuiScreen {
    private static final int[] COLOR_PALETTE = {
        0xFFFFFFFF, 0xFFFF4D4D, 0xFFFFD54F, 0xFF55FF88,
        0xFF55E6FF, 0xFF4D7CFF, 0xFFAA66FF
    };

    private final Screen parent;
    private final ClientKernel kernel;
    private final Module module;
    private KeybindSetting capturingKeybind;

    ModuleSettingsScreen(Screen parent, ClientKernel kernel, Module module) {
        super(Component.literal(module.metadata().name() + " Settings"));
        this.parent = parent;
        this.kernel = kernel;
        this.module = module;
    }

    @Override
    protected void init() {
        int buttonWidth = Math.clamp(width - 40, 160, 300);
        int x = (width - buttonWidth) / 2;
        int y = 43;
        addRenderableWidget(Button.builder(moduleLabel(), button -> {
            kernel.toggleModule(module.id(), kernel.context(minecraft));
            button.setMessage(moduleLabel());
        }).bounds(x, y, buttonWidth, 20).build());
        y += 27;

        List<Setting<?>> settings = module.settings().stream().filter(Setting::isVisible).toList();
        for (Setting<?> setting : settings) {
            addRenderableWidget(Button.builder(settingLabel(setting), button -> {
                if (setting instanceof KeybindSetting keybind) {
                    capturingKeybind = keybind;
                    button.setMessage(Component.literal(keybind.displayName() + ": press a key"));
                    return;
                }
                mutate(setting);
                kernel.saveConfiguration();
                button.setMessage(settingLabel(setting));
            }).bounds(x, y, buttonWidth, 20).build());
            y += 23;
        }

        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
            .bounds(width / 2 - 50, Math.min(height - 28, y + 5), 100, 20)
            .build());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (capturingKeybind != null) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                capturingKeybind = null;
                rebuildWidgets();
                return true;
            }
            int key = event.key();
            if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE
                || key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
                key = KeybindSetting.UNBOUND;
            }
            capturingKeybind.set(key);
            capturingKeybind = null;
            kernel.saveConfiguration();
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xD010131B);
        graphics.drawCenteredString(font, title, width / 2, 13, 0xFF4DE1FF);
        graphics.drawCenteredString(font, module.metadata().description(), width / 2, 26, 0xFFB8C2CC);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        kernel.saveConfiguration();
        minecraft.setScreen(parent);
    }

    @Override
    public void closeClickGui() {
        kernel.saveConfiguration();
        if (parent instanceof ClickGuiScreen clickGui) {
            clickGui.closeClickGui();
        } else {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void mutate(Setting<?> setting) {
        if (setting instanceof BooleanSetting booleanSetting) {
            booleanSetting.set(!booleanSetting.value());
        } else if (setting instanceof NumberSetting numberSetting) {
            double next = numberSetting.value() + numberSetting.step();
            numberSetting.set(next > numberSetting.maximum() + 1.0E-9
                ? numberSetting.minimum() : next);
        } else if (setting instanceof EnumSetting<?> enumSetting) {
            cycleEnum(enumSetting);
        } else if (setting instanceof ColorSetting colorSetting) {
            int index = 0;
            while (index < COLOR_PALETTE.length && COLOR_PALETTE[index] != colorSetting.value()) {
                index++;
            }
            colorSetting.set(COLOR_PALETTE[(index + 1) % COLOR_PALETTE.length]);
        }
    }

    private static <E extends Enum<E>> void cycleEnum(EnumSetting<E> setting) {
        E[] values = setting.values();
        int index = 0;
        while (index < values.length && values[index] != setting.value()) {
            index++;
        }
        setting.set(values[(index + 1) % values.length]);
    }

    private Component moduleLabel() {
        return Component.literal((module.isEnabled() ? "[ON] " : "[OFF] ")
            + module.metadata().name());
    }

    private static Component settingLabel(Setting<?> setting) {
        return Component.literal(setting.displayName() + ": " + setting.format());
    }
}
