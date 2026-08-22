package dev.liquidcopy.launcher;

import javax.swing.SwingUtilities;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;

public final class LauncherMain {
    private LauncherMain() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || "gui".equalsIgnoreCase(args[0])) {
            SwingUtilities.invokeLater(() -> new LauncherFrame(new InstallService()).setVisible(true));
            return;
        }
        int status = run(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args, PrintStream output, PrintStream error) {
        try {
            String command = canonicalCommand(args[0]);
            if ("help".equals(command) || "--help".equals(command) || "-h".equals(command)) {
                printUsage(output);
                return 0;
            }
            Path minecraftDirectory = parseMinecraftDirectory(Arrays.copyOfRange(args, 1, args.length));
            InstallService service = new InstallService();
            return switch (command) {
                case "install" -> {
                    InstallService.InstallReport report = service.install(minecraftDirectory);
                    output.println("INSTALL OK: " + report.minecraftDirectory());
                    report.messages().forEach(message -> output.println("  " + message));
                    output.println("Restart the Minecraft Launcher and select LiquidCopy 1.21.11.");
                    yield 0;
                }
                case "verify" -> {
                    InstallService.VerificationReport report = service.verify(minecraftDirectory);
                    output.println((report.valid() ? "VERIFY OK: " : "VERIFY FAILED: ") + minecraftDirectory);
                    report.messages().forEach(message -> output.println("  " + message));
                    yield report.valid() ? 0 : 2;
                }
                default -> {
                    error.println("Unknown command: " + command);
                    printUsage(error);
                    yield 64;
                }
            };
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            error.println("Operation interrupted");
            return 130;
        } catch (Exception exception) {
            error.println("Operation failed: " + exception.getMessage());
            return 1;
        }
    }

    static String canonicalCommand(String command) {
        String normalized = command.toLowerCase();
        return switch (normalized) {
            case "--install" -> "install";
            case "--verify" -> "verify";
            default -> normalized;
        };
    }

    private static Path parseMinecraftDirectory(String[] args) {
        Path result = MinecraftDirectories.defaultDirectory();
        for (int index = 0; index < args.length; index++) {
            if ("--minecraft-dir".equals(args[index])) {
                if (++index >= args.length) {
                    throw new IllegalArgumentException("--minecraft-dir requires a path");
                }
                result = Path.of(args[index]);
            } else {
                throw new IllegalArgumentException("Unknown option: " + args[index]);
            }
        }
        return result;
    }

    private static void printUsage(PrintStream output) {
        output.println("LiquidCopy launcher for Minecraft 1.21.11");
        output.println("Usage:");
        output.println("  java -jar LiquidCopy-Launcher.jar [gui]");
        output.println("  java -jar LiquidCopy-Launcher.jar install [--minecraft-dir PATH]");
        output.println("  java -jar LiquidCopy-Launcher.jar verify  [--minecraft-dir PATH]");
        output.println("  (--install and --verify are accepted aliases.)");
    }
}
