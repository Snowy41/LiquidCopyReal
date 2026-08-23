package dev.liquidcopy.launcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LauncherMainTest {
    @Test
    void acceptsScriptCompatibleCommandAliases() {
        assertEquals("install", LauncherMain.canonicalCommand("--install"));
        assertEquals("verify", LauncherMain.canonicalCommand("--verify"));
        assertEquals("play", LauncherMain.canonicalCommand("--play"));
        assertEquals("install", LauncherMain.canonicalCommand("INSTALL"));
    }

    @Test
    void parsesStandaloneDataAccountAndMemoryOptions() {
        LauncherMain.CliOptions options = LauncherMain.parseOptions(new String[] {
            "--data-dir", "custom-root",
            "--memory", "6144"
        });

        assertEquals(Path.of("custom-root").toAbsolutePath().normalize(), options.dataDirectory());
        assertEquals(6144, options.maxMemoryMiB());
    }

    @Test
    void rejectsUnknownCliOptions() {
        assertThrows(IllegalArgumentException.class,
            () -> LauncherMain.parseOptions(new String[] {"--open-external-launcher"}));
    }
}
