package dev.liquidcopy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class ClientEntrypointContractTest {
    @Test
    void injectedHooksHaveStableObjectOnlyDescriptors() throws Exception {
        assertHook("onTick", Object.class);
        assertHook("onHudRender", Object.class, Object.class, Object.class);
        assertHook("onBlockEntityExtract", Object.class, Object.class);
        assertHook("onEntityExtract", Object.class, Object.class);
    }

    private static void assertHook(String name, Class<?>... parameterTypes) throws Exception {
        Method method = ClientEntrypoint.class.getMethod(name, parameterTypes);
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertEquals(void.class, method.getReturnType());
    }
}
