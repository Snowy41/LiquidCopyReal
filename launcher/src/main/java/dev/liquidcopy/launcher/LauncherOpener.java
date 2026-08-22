package dev.liquidcopy.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/** Opens the native Minecraft Launcher and the isolated LiquidCopy game directory. */
final class LauncherOpener {
    static final String WINDOWS_APP_ID = "Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft";

    private final Platform platform;
    private final Predicate<String> commandAvailable;
    private final CommandStarter starter;

    LauncherOpener(Platform platform, Predicate<String> commandAvailable, CommandStarter starter) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.commandAvailable = Objects.requireNonNull(commandAvailable, "commandAvailable");
        this.starter = Objects.requireNonNull(starter, "starter");
    }

    static LauncherOpener system() {
        return new LauncherOpener(
            Platform.from(System.getProperty("os.name", "")),
            LauncherOpener::isOnPath,
            command -> new ProcessBuilder(command).start()
        );
    }

    void openMinecraftLauncher() throws IOException {
        IOException failure = null;
        for (List<String> command : launcherCommands()) {
            try {
                starter.start(command);
                return;
            } catch (IOException exception) {
                if (failure == null) {
                    failure = new IOException("Unable to open the Minecraft Launcher");
                }
                failure.addSuppressed(exception);
            }
        }
        throw failure == null ? new IOException("No Minecraft Launcher command is available") : failure;
    }

    void openInstanceDirectory(Path minecraftDirectory) throws IOException {
        Path instance = InstallService.instanceDirectory(minecraftDirectory);
        Files.createDirectories(instance);
        starter.start(directoryCommand(instance));
    }

    List<List<String>> launcherCommands() {
        return switch (platform) {
            case WINDOWS -> List.of(
                List.of("explorer.exe", "shell:AppsFolder\\" + WINDOWS_APP_ID),
                List.of("rundll32.exe", "url.dll,FileProtocolHandler", "minecraft://")
            );
            case MAC -> List.of(
                List.of("open", "minecraft://"),
                List.of("open", "-a", "Minecraft Launcher")
            );
            case LINUX -> commandAvailable.test("minecraft-launcher")
                ? List.of(List.of("minecraft-launcher"), List.of("xdg-open", "minecraft://"))
                : List.of(List.of("xdg-open", "minecraft://"));
        };
    }

    List<String> directoryCommand(Path directory) {
        String absolute = directory.toAbsolutePath().normalize().toString();
        return switch (platform) {
            case WINDOWS -> List.of("explorer.exe", absolute);
            case MAC -> List.of("open", absolute);
            case LINUX -> List.of("xdg-open", absolute);
        };
    }

    private static boolean isOnPath(String command) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) {
                Path candidate = Path.of(entry).resolve(command);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    enum Platform {
        WINDOWS,
        MAC,
        LINUX;

        static Platform from(String osName) {
            String value = osName.toLowerCase(Locale.ROOT);
            if (value.contains("win")) {
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
