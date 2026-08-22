package dev.liquidcopy.api.module;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ModuleRegistryTest {
    @Test
    void isolatesTickFailures() {
        List<String> failures = new ArrayList<>();
        ModuleRegistry registry = new ModuleRegistry((module, failure) -> failures.add(module.id()));
        Module broken = new Module(
            new ModuleMetadata("broken", "Broken", "test", ModuleCategory.CLIENT, false), -1
        ) {
            @Override
            protected void onTick(ModuleContext context) {
                throw new IllegalStateException("boom");
            }
        };
        registry.register(broken);
        registry.setEnabled("broken", true, ModuleContext.EMPTY);

        registry.tick(ModuleContext.EMPTY);

        assertFalse(broken.isEnabled());
        assertTrue(failures.contains("broken"));
    }
}

