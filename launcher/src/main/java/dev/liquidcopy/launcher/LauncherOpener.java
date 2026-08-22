package dev.liquidcopy.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Opens launcher-owned directories with the native file manager. */
final class LauncherOpener {
    private final Platform platform;
    private final CommandStarter starter;

    LauncherOpener(Platform platform, CommandStarter starter) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.starter = Objects.requireNonNull(starter, "starter");
    }

    static LauncherOpener system() {
        return new LauncherOpener(
            Platform.from(System.getProperty("os.name", "")),
            command -> new ProcessBuilder(command).start()
        );
    }

    void openInstanceDirectory(Path dataDirectory) throws IOException {
        Path instance = InstallService.instanceDirectory(dataDirectory);
        Files.createDirectories(instance);
        starter.start(directoryCommand(instance));
    }

    List<String> directoryCommand(Path directory) {
        String absolute = directory.toAbsolutePath().normalize().toString();
        return switch (platform) {
            case WINDOWS -> List.of("explorer.exe", absolute);
            case MAC -> List.of("open", absolute);
            case LINUX -> List.of("xdg-open", absolute);
        };
    }

    enum Platform {
        WINDOWS,
        MAC,
        LINUX;

        static Platform from(String osName) {
            String value = osName.toLowerCase(Locale.ROOT);
            if (value.startsWith("windows")) {
                return WINDOWS;
            }
            if (value.contains("mac") || value.contains("darwin")) {
                return MAC;
            }
            return LINUX;
        }
    }

    @FunctionalInterface
    interface CommandStarter {
        void start(List<String> command) throws IOException;
    }
}
