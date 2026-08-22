package dev.liquidcopy.launcher;

import java.nio.file.Path;
import java.util.Locale;

public final class MinecraftDirectories {
    public static final String DATA_DIRECTORY_PROPERTY = "liquidcopy.dataDir";

    private MinecraftDirectories() {
    }

    public static Path defaultDirectory() {
        String override = System.getProperty(DATA_DIRECTORY_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return directoryFor(
            System.getProperty("os.name", ""),
            System.getProperty("user.home", "."),
            System.getenv("APPDATA"),
            System.getenv("XDG_DATA_HOME")
        );
    }

    static Path directoryFor(String osName, String home, String appData, String xdgDataHome) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return Path.of(appData == null || appData.isBlank() ? home : appData, "LiquidCopy")
                .toAbsolutePath().normalize();
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "LiquidCopy").toAbsolutePath().normalize();
        }
        Path dataHome = xdgDataHome == null || xdgDataHome.isBlank()
            ? Path.of(home, ".local", "share")
            : Path.of(xdgDataHome);
        return dataHome.resolve("liquidcopy").toAbsolutePath().normalize();
    }
}
