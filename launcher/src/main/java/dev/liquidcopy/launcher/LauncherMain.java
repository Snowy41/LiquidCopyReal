package dev.liquidcopy.launcher;

import javax.swing.SwingUtilities;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

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

            CliOptions options = parseOptions(Arrays.copyOfRange(args, 1, args.length));
            Path dataDirectory = options.dataDirectory();
            LauncherSettings saved = LauncherSettings.load(dataDirectory);
            LauncherSettings settings = new LauncherSettings(
                options.clientId() == null ? saved.microsoftClientId() : options.clientId(),
                options.maxMemoryMiB() == null ? saved.maxMemoryMiB() : options.maxMemoryMiB()
            );
            InstallService installer = new InstallService();

            return switch (command) {
                case "install" -> install(installer, dataDirectory, settings, output);
                case "verify" -> verify(installer, dataDirectory, output);
                case "login" -> login(dataDirectory, settings, output);
                case "account" -> account(dataDirectory, output);
                case "logout" -> logout(dataDirectory, output);
                case "play" -> play(installer, dataDirectory, settings, output);
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

    private static int install(
        InstallService installer,
        Path dataDirectory,
        LauncherSettings settings,
        PrintStream output
    ) throws Exception {
        settings.save(dataDirectory);
        InstallService.InstallReport report = installer.install(dataDirectory);
        output.println("INSTALL OK: " + report.dataDirectory());
        report.messages().forEach(message -> output.println("  " + message));
        output.println("LiquidCopy is ready for direct launch.");
        return 0;
    }

    private static int verify(InstallService installer, Path dataDirectory, PrintStream output) {
        InstallService.VerificationReport report = installer.verify(dataDirectory);
        output.println((report.valid() ? "VERIFY OK: " : "VERIFY FAILED: ") + dataDirectory);
        report.messages().forEach(message -> output.println("  " + message));
        return report.valid() ? 0 : 2;
    }

    private static int login(Path dataDirectory, LauncherSettings settings, PrintStream output) throws Exception {
        requireClientId(settings);
        settings.save(dataDirectory);
        MinecraftAccount account = authService(dataDirectory, settings).loginWithBrowser(
            progress -> output.println("AUTH " + progress.stage() + ": " + progress.message())
        );
        output.println("LOGIN OK: " + account.username() + " (" + account.uuid() + ")");
        return 0;
    }

    private static int account(Path dataDirectory, PrintStream output) throws Exception {
        Optional<MinecraftAccount> saved = AccountStore.inDirectory(dataDirectory).load();
        if (saved.isEmpty()) {
            output.println("ACCOUNT: not signed in");
            return 3;
        }
        MinecraftAccount account = saved.get();
        output.println("ACCOUNT: " + account.username() + " (" + account.uuid() + ")");
        output.println("SESSION EXPIRES: " + account.minecraftAccessTokenExpiresAt());
        return 0;
    }

    private static int logout(Path dataDirectory, PrintStream output) throws Exception {
        AccountStore.inDirectory(dataDirectory).clear();
        output.println("LOGOUT OK: local Microsoft session removed");
        return 0;
    }

    private static int play(
        InstallService installer,
        Path dataDirectory,
        LauncherSettings settings,
        PrintStream output
    ) throws Exception {
        requireClientId(settings);
        settings.save(dataDirectory);
        MicrosoftAuthService auth = authService(dataDirectory, settings);
        MinecraftAccount account = auth.accountForLaunch(
            progress -> output.println("AUTH " + progress.stage() + ": " + progress.message())
        ).orElseThrow(() -> new IllegalStateException("No Microsoft account is saved; run login first"));
        if (!account.clientId().equals(settings.microsoftClientId())) {
            throw new IllegalStateException("The Microsoft application ID changed; run logout, then login");
        }

        InstallService.VerificationReport verification = installer.verify(dataDirectory);
        if (!verification.valid()) {
            output.println("Installation is missing or outdated; installing now…");
            installer.install(dataDirectory);
        }

        DirectLaunchService.AuthenticatedAccount launchAccount = new DirectLaunchService.AuthenticatedAccount(
            account.username(), account.uuid(), account.minecraftAccessToken(), account.xuid(), account.clientId()
        );
        DirectLaunchService service = new DirectLaunchService();
        DirectLaunchService.LaunchResult result = service.launch(
            dataDirectory,
            launchAccount,
            DirectLaunchService.LaunchOptions.withMaxMemoryMiB(settings.maxMemoryMiB()),
            progress -> output.println("RUNTIME " + progress.phase() + ": " + progress.item()
                + (progress.total() > 0 ? " (" + progress.completed() + "/" + progress.total() + ")" : ""))
        );
        output.println("PLAY OK: PID " + result.process().pid());
        output.println("GAME LOG: " + result.preparation().logFile());
        return 0;
    }

    private static MicrosoftAuthService authService(Path dataDirectory, LauncherSettings settings) {
        return MicrosoftAuthService.create(dataDirectory, new MicrosoftAuthConfig(settings.microsoftClientId()));
    }

    private static void requireClientId(LauncherSettings settings) {
        if (settings.microsoftClientId().isBlank()) {
            throw new IllegalStateException("Microsoft application ID is missing; use --client-id or set it in the GUI");
        }
    }

    static String canonicalCommand(String command) {
        String normalized = command.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "--install" -> "install";
            case "--verify" -> "verify";
            case "--play" -> "play";
            default -> normalized;
        };
    }

    static CliOptions parseOptions(String[] args) {
        Path dataDirectory = MinecraftDirectories.defaultDirectory();
        String clientId = null;
        Integer memory = null;
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            switch (option) {
                case "--data-dir", "--minecraft-dir" -> {
                    if (++index >= args.length) {
                        throw new IllegalArgumentException(option + " requires a path");
                    }
                    dataDirectory = Path.of(args[index]);
                }
                case "--client-id" -> {
                    if (++index >= args.length) {
                        throw new IllegalArgumentException("--client-id requires an application ID");
                    }
                    clientId = args[index];
                }
                case "--memory" -> {
                    if (++index >= args.length) {
                        throw new IllegalArgumentException("--memory requires MiB");
                    }
                    memory = Integer.parseInt(args[index]);
                }
                default -> throw new IllegalArgumentException("Unknown option: " + option);
            }
        }
        return new CliOptions(dataDirectory.toAbsolutePath().normalize(), clientId, memory);
    }

    private static void printUsage(PrintStream output) {
        output.println("Standalone LiquidCopy launcher for Minecraft 1.21.11");
        output.println("Usage:");
        output.println("  java -jar LiquidCopy-Launcher.jar [gui]");
        output.println("  java -jar LiquidCopy-Launcher.jar install [--data-dir PATH]");
        output.println("  java -jar LiquidCopy-Launcher.jar verify  [--data-dir PATH]");
        output.println("  java -jar LiquidCopy-Launcher.jar login   [--data-dir PATH] [--client-id ID]");
        output.println("  java -jar LiquidCopy-Launcher.jar account [--data-dir PATH]");
        output.println("  java -jar LiquidCopy-Launcher.jar logout  [--data-dir PATH]");
        output.println("  java -jar LiquidCopy-Launcher.jar play    [--data-dir PATH] [--client-id ID] [--memory MiB]");
        output.println("  (--install, --verify, and --play are accepted aliases.)");
    }

    record CliOptions(Path dataDirectory, String clientId, Integer maxMemoryMiB) { }
}
