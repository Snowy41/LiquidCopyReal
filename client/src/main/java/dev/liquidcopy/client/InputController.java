package dev.liquidcopy.client;

import dev.liquidcopy.api.module.Module;
import dev.liquidcopy.api.setting.KeybindSetting;
import dev.liquidcopy.client.gui.ClickGuiScreen;
import dev.liquidcopy.client.gui.ModuleSelectorScreen;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

final class InputController {
    private static final Map<String, Boolean> KEY_STATES = new HashMap<>();
    private static boolean clickGuiDown;

    private InputController() {
    }

    static void poll(ClientKernel.RuntimeContext context) {
        if (!(context.minecraft() instanceof Minecraft minecraft)) {
            return;
        }
        long window = minecraft.getWindow().handle();
        boolean clickGuiPressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (clickGuiPressed && !clickGuiDown) {
            if (minecraft.screen instanceof ClickGuiScreen clickGui) {
                clickGui.closeClickGui();
            } else {
                minecraft.setScreen(new ModuleSelectorScreen(minecraft.screen, context.kernel()));
            }
        }
        clickGuiDown = clickGuiPressed;

        boolean acceptModuleHotkeys = minecraft.screen == null;
        for (Module module : context.kernel().modules().all()) {
            int key = module.keybind().value();
            if (key == KeybindSetting.UNBOUND || key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
                KEY_STATES.remove(module.id());
                continue;
            }
            boolean pressed = GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
            boolean wasPressed = KEY_STATES.put(module.id(), pressed) == Boolean.TRUE;
            if (acceptModuleHotkeys && pressed && !wasPressed) {
                context.kernel().toggleModule(module.id(), context);
            }
        }
    }
}
