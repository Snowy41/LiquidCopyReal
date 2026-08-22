package dev.liquidcopy.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherOpenerTest {
    @TempDir
    Path minecraftDirectory;

    @Test
    void selectsWindowsAppsFolderAndExplorerCommands() {
        LauncherOpener opener = new LauncherOpener(LauncherOpener.Platform.WINDOWS, command -> false, command -> { });

        assertEquals(List.of("explorer.exe", "shell:AppsFolder\\" + LauncherOpener.WINDOWS_APP_ID),
            opener.launcherCommands().get(0));
        assertEquals(List.of("explorer.exe", minecraftDirectory.toAbsolutePath().normalize().toString()),
            opener.directoryCommand(minecraftDirectory));
    }

    @Test
    void prefersLinuxLauncherExecutableAndFallsBackToMinecraftUri() {
        LauncherOpener executable = new LauncherOpener(
            LauncherOpener.Platform.LINUX, "minecraft-launcher"::equals, command -> { });
        LauncherOpener uri = new LauncherOpener(LauncherOpener.Platform.LINUX, command -> false, command -> { });

        assertEquals(List.of("minecraft-launcher"), executable.launcherCommands().get(0));
        assertEquals(List.of("xdg-open", "minecraft://"), uri.launcherCommands().get(0));
    }

    @Test
    void createsAndOpensIsolatedInstanceDirectory() throws Exception {
        List<List<String>> invoked = new ArrayList<>();
        LauncherOpener opener = new LauncherOpener(LauncherOpener.Platform.LINUX, command -> false, invoked::add);

        opener.openInstanceDirectory(minecraftDirectory);

        Path instance = InstallService.instanceDirectory(minecraftDirectory);
        assertTrue(Files.isDirectory(instance));
        assertEquals(List.of("xdg-open", instance.toString()), invoked.get(0));
    }

    @Test
    void detectsCommonOperatingSystemNames() {
        assertEquals(LauncherOpener.Platform.WINDOWS, LauncherOpener.Platform.from("Windows 11"));
        assertEquals(LauncherOpener.Platform.MAC, LauncherOpener.Platform.from("Mac OS X"));
        assertEquals(LauncherOpener.Platform.LINUX, LauncherOpener.Platform.from("Linux"));
    }
}
