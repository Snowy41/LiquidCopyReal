package dev.liquidcopy.launcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinecraftDirectoriesTest {
    @Test
    void usesLauncherOwnedWindowsDirectory() {
        assertEquals(Path.of("C:/Users/Alex/AppData/Roaming/LiquidCopy").toAbsolutePath().normalize(),
            MinecraftDirectories.directoryFor("Windows 11", "C:/Users/Alex", "C:/Users/Alex/AppData/Roaming", null));
    }

    @Test
    void usesApplicationSupportOnMac() {
        assertEquals(Path.of("/Users/alex/Library/Application Support/LiquidCopy").toAbsolutePath().normalize(),
            MinecraftDirectories.directoryFor("Mac OS X", "/Users/alex", null, null));
    }

    @Test
    void honoursXdgDataHomeOnLinux() {
        assertEquals(Path.of("/custom/data/liquidcopy").toAbsolutePath().normalize(),
            MinecraftDirectories.directoryFor("Linux", "/home/alex", null, "/custom/data"));
    }
}
