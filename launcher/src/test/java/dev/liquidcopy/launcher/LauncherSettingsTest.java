package dev.liquidcopy.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LauncherSettingsTest {
    @TempDir
    Path dataDirectory;

    @Test
    void savesAndLoadsRootScopedSettings() throws Exception {
        LauncherSettings expected = new LauncherSettings(6_144);

        expected.save(dataDirectory);

        assertEquals(expected, LauncherSettings.load(dataDirectory));
        assertEquals(dataDirectory.resolve(LauncherSettings.FILE_NAME), LauncherSettings.settingsFile(dataDirectory));
    }

    @Test
    void defaultsWhenSettingsFileDoesNotExist() throws Exception {
        assertEquals(LauncherSettings.DEFAULT_MEMORY_MIB, LauncherSettings.load(dataDirectory).maxMemoryMiB());
    }
}
