package dev.liquidcopy.launcher;

import java.nio.file.Path;
import java.util.Locale;

public final class MinecraftDirectories {
    private MinecraftDirectories() {
    }

    public static Path defaultDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData == null || appData.isBlank() ? home : appData, ".minecraft");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "minecraft");
        }
        return Path.of(home, ".minecraft");
    }
}
