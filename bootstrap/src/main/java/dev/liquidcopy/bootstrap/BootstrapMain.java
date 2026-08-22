package dev.liquidcopy.bootstrap;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;

/** Entry point and Java agent for the official named 1.21.11 client. */
public final class BootstrapMain {
    public static final String TARGET_VERSION = "1.21.11";
    private static volatile boolean agentReady;

    private BootstrapMain() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        HookTransformer.verifyOfficialClasses(ClassLoader.getSystemClassLoader());
        instrumentation.addTransformer(new HookTransformer(), false);
        agentReady = true;
        System.setProperty("liquidcopy.agent", "active");
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        premain(arguments, instrumentation);
    }

    public static void main(String[] args) throws Exception {
        if (!agentReady) {
            throw new IllegalStateException("LiquidCopy must be started through its installed 1.21.11 profile");
        }
        System.setProperty("liquidcopy.version", TARGET_VERSION);
        try {
            Class<?> main = Class.forName("net.minecraft.client.main.Main");
            main.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception nested) {
                throw nested;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }
}
