package dev.liquidcopy.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LauncherMainTest {
    @Test
    void acceptsScriptCompatibleCommandAliases() {
        assertEquals("install", LauncherMain.canonicalCommand("--install"));
        assertEquals("verify", LauncherMain.canonicalCommand("--verify"));
        assertEquals("install", LauncherMain.canonicalCommand("INSTALL"));
    }
}
