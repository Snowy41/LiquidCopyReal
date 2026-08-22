package dev.liquidcopy.launcher;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone account, installation, and direct-launch UI. */
final class LauncherFrame extends JFrame {
    private static final URI APP_REGISTRATION_URI = URI.create(
        "https://entra.microsoft.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade"
    );
    private static final int MAX_LOG_CHARACTERS = 250_000;

    private final InstallService installService;
    private final DirectLaunchService launchService;
    private final LauncherOpener opener;
    private final SystemBrowser browser;
    private final JTextField directory = new JTextField(MinecraftDirectories.defaultDirectory().toString(), 42);
    private final JTextField clientId = new JTextField(38);
    private final JSpinner maxMemory = new JSpinner(new SpinnerNumberModel(
        LauncherSettings.DEFAULT_MEMORY_MIB,
        LauncherSettings.MIN_MEMORY_MIB,
        LauncherSettings.MAX_MEMORY_MIB,
        512
    ));
    private final JLabel accountStatus = new JLabel("Not signed in");
    private final JTextArea log = new JTextArea();
    private final JButton browse = new JButton("Browse…");
    private final JButton registration = new JButton("Register application…");
    private final JButton saveSettings = new JButton("Save settings");
    private final JButton signIn = new JButton("Sign in with Microsoft");
    private final JButton cancelSignIn = new JButton("Cancel sign-in");
    private final JButton copySignInUrl = new JButton("Copy sign-in URL");
    private final JButton signOut = new JButton("Sign out");
    private final JButton install = new JButton("Install / Update");
    private final JButton verify = new JButton("Verify");
    private final JButton play = new JButton("Play");
    private final JButton openInstance = new JButton("Open game folder");

    private volatile MinecraftAccount account;
    private volatile Path accountDirectory;
    private volatile Process gameProcess;
    private volatile URI latestAuthorizationUri;
    private volatile SwingWorker<String, Void> activeWorker;
    private boolean busy;
    private boolean authenticationBusy;

    LauncherFrame(InstallService installService) {
        this(installService, new DirectLaunchService(), LauncherOpener.system(), SystemBrowser.desktop());
    }

    LauncherFrame(
        InstallService installService,
        DirectLaunchService launchService,
        LauncherOpener opener,
        SystemBrowser browser
    ) {
        super("LiquidCopy 1.21.11");
        this.installService = Objects.requireNonNull(installService, "installService");
        this.launchService = Objects.requireNonNull(launchService, "launchService");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.browser = Objects.requireNonNull(browser, "browser");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(840, 610));
        setLocationByPlatform(true);
        buildUi();
        pack();
        loadSelectedContext();
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel heading = new JPanel(new BorderLayout(4, 4));
        JLabel title = new JLabel("LiquidCopy 1.21.11");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        heading.add(title, BorderLayout.NORTH);
        heading.add(new JLabel("Standalone Microsoft sign-in, installation, updates, and direct game launch"),
            BorderLayout.SOUTH);
        root.add(heading, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(10, 10));
        body.add(configurationPanel(), BorderLayout.NORTH);

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setText("Ready. LiquidCopy downloads and starts Minecraft itself; no external launcher is used.\n");
        JScrollPane logPane = new JScrollPane(log);
        logPane.setBorder(BorderFactory.createTitledBorder("Launcher log"));
        body.add(logPane, BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(openInstance);
        buttons.add(verify);
        buttons.add(install);
        buttons.add(play);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);

        browse.addActionListener(event -> chooseDirectory());
        directory.addActionListener(event -> loadSelectedContext());
        registration.addActionListener(event -> openRegistrationPage());
        signIn.addActionListener(event -> signIn());
        cancelSignIn.addActionListener(event -> cancelSignIn());
        copySignInUrl.addActionListener(event -> copySignInUrl());
        signOut.addActionListener(event -> signOut());
        install.addActionListener(event -> install());
        verify.addActionListener(event -> verify());
        play.addActionListener(event -> play());
        saveSettings.addActionListener(event -> saveSettings());
        openInstance.addActionListener(event -> executeWithContext("Open game folder", false, context -> {
            opener.openInstanceDirectory(context.dataDirectory());
            return "Opened " + InstallService.instanceDirectory(context.dataDirectory());
        }));
        updateControls();
    }

    private JPanel configurationPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Launcher settings"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 6, 4, 6);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;

        addRow(panel, constraints, 0, "LiquidCopy data directory", directory, browse);
        addRow(panel, constraints, 1, "Microsoft application (client) ID", clientId, registration);

        JPanel accountActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        accountActions.add(signIn);
        accountActions.add(cancelSignIn);
        accountActions.add(signOut);
        accountActions.add(copySignInUrl);
        addRow(panel, constraints, 2, "Account", accountStatus, accountActions);

        JPanel memory = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        memory.add(maxMemory);
        memory.add(new JLabel(" MiB maximum"));
        addRow(panel, constraints, 3, "Game memory", memory, saveSettings);

        JLabel help = new JLabel("<html>Use the distributor's own public desktop-app ID with the "
            + "<b>http://localhost</b> redirect URI. It must be accepted for Xbox Live/Minecraft Services; "
            + "do not borrow another launcher's ID. Credentials are entered only in your system browser.</html>");
        constraints.gridx = 1;
        constraints.gridy = 4;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        panel.add(help, constraints);
        return panel;
    }

    private static void addRow(
        JPanel panel,
        GridBagConstraints constraints,
        int row,
        String label,
        java.awt.Component value,
        java.awt.Component action
    ) {
        constraints.gridy = row;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        constraints.gridx = 0;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(value, constraints);
        constraints.gridx = 2;
        constraints.weightx = 0;
        panel.add(action, constraints);
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser(selectedDirectory().toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select LiquidCopy data directory");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            directory.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
            loadSelectedContext();
        }
    }

    private void openRegistrationPage() {
        execute("Open application registration", () -> {
            browser.open(APP_REGISTRATION_URI);
            return "Opened Microsoft Entra application registrations. Configure a public desktop application "
                + "with the http://localhost redirect URI, then paste its application ID above.";
        });
    }

    private void signIn() {
        latestAuthorizationUri = null;
        executeWithContext("Microsoft sign-in", true, true, context -> {
            context.settings().save(context.dataDirectory());
            MinecraftAccount signedIn = authService(context).loginWithBrowser(this::reportAuthProgress);
            setAccount(signedIn, context.dataDirectory());
            return "Signed in as " + signedIn.username() + ".";
        });
    }

    private void cancelSignIn() {
        SwingWorker<String, Void> worker = activeWorker;
        if (authenticationBusy && worker != null && worker.cancel(true)) {
            appendLog("Cancelling Microsoft sign-in…");
            updateControls();
        }
    }

    private void copySignInUrl() {
        URI uri = latestAuthorizationUri;
        if (uri == null) {
            appendLog("No Microsoft authorization URL is available yet.");
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(uri.toASCIIString()), null);
            appendLog("Copied the Microsoft authorization URL to the clipboard.");
        } catch (RuntimeException exception) {
            appendLog("Clipboard copy failed. Sign-in URL: " + uri.toASCIIString());
        }
    }

    private void signOut() {
        executeWithContext("Sign out", false, context -> {
            String effectiveClientId = context.settings().microsoftClientId();
            MinecraftAccount current = accountFor(context.dataDirectory()).orElse(null);
            if (effectiveClientId.isBlank() && current != null) {
                effectiveClientId = current.clientId();
            }
            if (effectiveClientId.isBlank()) {
                AccountStore.inDirectory(context.dataDirectory()).clear();
            } else {
                MicrosoftAuthService.create(context.dataDirectory(), new MicrosoftAuthConfig(effectiveClientId))
                    .logout();
            }
            setAccount(null, context.dataDirectory());
            return "Local Microsoft session removed.";
        });
    }

    private void install() {
        executeWithContext("Install / Update", false, context -> {
            context.settings().save(context.dataDirectory());
            InstallService.InstallReport report = installService.install(context.dataDirectory());
            return "Installed " + ProfileComposer.CUSTOM_VERSION_ID + " in " + report.dataDirectory() + "\n"
                + String.join("\n", report.messages())
                + "\nLiquidCopy is ready for direct launch.";
        });
    }

    private void verify() {
        executeWithContext("Verify", false, context -> {
            context.settings().save(context.dataDirectory());
            InstallService.VerificationReport report = installService.verify(context.dataDirectory());
            return (report.valid() ? "Verification succeeded" : "Verification failed") + "\n"
                + String.join("\n", report.messages());
        });
    }

    private void play() {
        executeWithContext("Play", true, context -> {
            context.settings().save(context.dataDirectory());
            MinecraftAccount launchAccount = authService(context).accountForLaunch(this::reportAuthProgress)
                .orElseThrow(() -> new IllegalStateException("Sign in with Microsoft before playing."));
            if (!launchAccount.clientId().equals(context.settings().microsoftClientId())) {
                throw new IllegalStateException("The application ID changed. Sign out and sign in again.");
            }
            setAccount(launchAccount, context.dataDirectory());

            InstallService.VerificationReport verification = installService.verify(context.dataDirectory());
            if (!verification.valid()) {
                appendLog("Installation is missing or outdated; installing before launch…");
                installService.install(context.dataDirectory());
            }

            DirectLaunchService.AuthenticatedAccount runtimeAccount = new DirectLaunchService.AuthenticatedAccount(
                launchAccount.username(), launchAccount.uuid(), launchAccount.minecraftAccessToken(),
                launchAccount.xuid(), launchAccount.clientId()
            );
            DirectLaunchService.LaunchOptions options =
                DirectLaunchService.LaunchOptions.withMaxMemoryMiB(context.settings().maxMemoryMiB());
            DirectLaunchService.LaunchResult result = launchService.launch(
                context.dataDirectory(), runtimeAccount, options, launchProgressLogger()
            );
            gameProcess = result.process();
            watchGameProcess(result.process());
            return "Minecraft started directly (PID " + result.process().pid() + ").\nGame log: "
                + result.preparation().logFile();
        });
    }

    private void saveSettings() {
        executeWithContext("Save settings", false, context -> {
            context.settings().save(context.dataDirectory());
            return "Saved launcher settings to " + LauncherSettings.settingsFile(context.dataDirectory()) + ".";
        });
    }

    private DirectLaunchService.ProgressListener launchProgressLogger() {
        AtomicReference<String> lastPhase = new AtomicReference<>("");
        AtomicInteger lastPercent = new AtomicInteger(-10);
        return progress -> {
            int percent = progress.total() <= 0 ? 0 : progress.completed() * 100 / progress.total();
            boolean phaseChanged = !progress.phase().equals(lastPhase.getAndSet(progress.phase()));
            boolean milestone = progress.completed() == progress.total() || percent >= lastPercent.get() + 10;
            if (phaseChanged || milestone) {
                lastPercent.set(percent);
                String counter = progress.total() > 0
                    ? " (" + progress.completed() + "/" + progress.total() + ")"
                    : "";
                appendLog("[" + progress.phase() + "] " + progress.item() + counter);
            }
        };
    }

    private void reportAuthProgress(AuthProgress progress) {
        appendLog("[Microsoft] " + progress.message());
        if (progress.browserUri() != null) {
            latestAuthorizationUri = progress.browserUri();
            appendLog("[Microsoft] Sign-in URL: " + progress.browserUri().toASCIIString());
            SwingUtilities.invokeLater(this::updateControls);
        }
    }

    private MicrosoftAuthService authService(LauncherContext context) {
        String id = context.settings().microsoftClientId();
        if (id.isBlank()) {
            throw new IllegalStateException("Enter your Microsoft application (client) ID first.");
        }
        return MicrosoftAuthService.create(context.dataDirectory(), new MicrosoftAuthConfig(id));
    }

    private LauncherContext captureContext(boolean requireClientId) {
        Path root = selectedDirectory();
        try {
            maxMemory.commitEdit();
        } catch (java.text.ParseException exception) {
            throw new IllegalArgumentException("Game memory is not a number", exception);
        }
        LauncherSettings settings = new LauncherSettings(clientId.getText(), ((Number) maxMemory.getValue()).intValue());
        if (requireClientId && settings.microsoftClientId().isBlank()) {
            throw new IllegalStateException("Enter your Microsoft application (client) ID first.");
        }
        return new LauncherContext(root, settings);
    }

    private Path selectedDirectory() {
        String value = directory.getText().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Select a LiquidCopy data directory.");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private void loadSelectedContext() {
        Path root;
        try {
            root = selectedDirectory();
        } catch (RuntimeException exception) {
            appendLog("Data directory is invalid: " + exception.getMessage());
            return;
        }
        account = null;
        accountDirectory = root;
        updateControls();
        new SwingWorker<LoadedContext, Void>() {
            @Override
            protected LoadedContext doInBackground() throws Exception {
                return new LoadedContext(LauncherSettings.load(root), AccountStore.inDirectory(root).load());
            }

            @Override
            protected void done() {
                try {
                    if (!selectedDirectory().equals(root)) {
                        return;
                    }
                    LoadedContext loaded = get();
                    String id = loaded.settings().microsoftClientId();
                    if (id.isBlank() && loaded.account().isPresent()) {
                        id = loaded.account().get().clientId();
                    }
                    clientId.setText(id);
                    maxMemory.setValue(loaded.settings().maxMemoryMiB());
                    setAccount(loaded.account().orElse(null), root);
                    appendLog(loaded.account().isPresent()
                        ? "Loaded saved account " + loaded.account().get().username() + "."
                        : "No saved Microsoft account in " + root + ".");
                } catch (Exception exception) {
                    appendLog("Unable to load launcher state: " + message(exception));
                    updateControls();
                }
            }
        }.execute();
    }

    private Optional<MinecraftAccount> accountFor(Path root) {
        MinecraftAccount current = account;
        return current != null && root.equals(accountDirectory) ? Optional.of(current) : Optional.empty();
    }

    private void setAccount(MinecraftAccount value, Path root) {
        Runnable update = () -> {
            account = value;
            accountDirectory = root;
            if (value == null) {
                accountStatus.setText("Not signed in");
                accountStatus.setToolTipText(null);
            } else {
                accountStatus.setText("Signed in as " + value.username());
                accountStatus.setToolTipText("Minecraft UUID: " + value.uuid());
            }
            updateControls();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    private void watchGameProcess(Process process) {
        process.onExit().thenAccept(completed -> SwingUtilities.invokeLater(() -> {
            if (gameProcess == process) {
                gameProcess = null;
            }
            appendLog("Minecraft exited with status " + completed.exitValue() + ".");
            updateControls();
        }));
    }

    private void execute(String label, Callable<String> operation) {
        execute(label, operation, false);
    }

    private void execute(String label, Callable<String> operation, boolean cancellableAuthentication) {
        appendLog(label + " started…");
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                return operation.call();
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled()) {
                        appendLog(label + " cancelled.");
                    } else {
                        appendLog(get());
                    }
                } catch (CancellationException exception) {
                    appendLog(label + " cancelled.");
                } catch (Exception exception) {
                    appendLog(label + " failed: " + message(exception));
                } finally {
                    if (activeWorker == this) {
                        activeWorker = null;
                        authenticationBusy = false;
                    }
                    setBusy(false);
                }
            }
        };
        activeWorker = worker;
        authenticationBusy = cancellableAuthentication;
        setBusy(true);
        worker.execute();
    }

    private void executeWithContext(String label, boolean requireClientId, ContextOperation operation) {
        executeWithContext(label, requireClientId, false, operation);
    }

    private void executeWithContext(
        String label,
        boolean requireClientId,
        boolean cancellableAuthentication,
        ContextOperation operation
    ) {
        try {
            LauncherContext context = captureContext(requireClientId);
            execute(label, () -> operation.call(context), cancellableAuthentication);
        } catch (RuntimeException exception) {
            appendLog(label + " failed: " + message(exception));
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        updateControls();
    }

    private void updateControls() {
        boolean signedIn = accountForSilently();
        boolean gameRunning = gameProcess != null && gameProcess.isAlive();
        install.setEnabled(!busy);
        verify.setEnabled(!busy);
        browse.setEnabled(!busy);
        registration.setEnabled(!busy);
        saveSettings.setEnabled(!busy);
        signIn.setEnabled(!busy);
        cancelSignIn.setEnabled(busy && authenticationBusy && activeWorker != null);
        copySignInUrl.setEnabled(latestAuthorizationUri != null);
        signOut.setEnabled(!busy && signedIn);
        play.setEnabled(!busy && signedIn && !gameRunning);
        openInstance.setEnabled(!busy);
        directory.setEnabled(!busy);
        clientId.setEnabled(!busy);
        maxMemory.setEnabled(!busy);
    }

    private boolean accountForSilently() {
        try {
            return accountFor(selectedDirectory()).isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void appendLog(String message) {
        Runnable append = () -> {
            log.append(message + System.lineSeparator());
            int excess = log.getDocument().getLength() - MAX_LOG_CHARACTERS;
            if (excess > 0) {
                try {
                    log.getDocument().remove(0, excess);
                } catch (javax.swing.text.BadLocationException ignored) {
                    // A concurrent document update will be trimmed on the next line.
                }
            }
            log.setCaretPosition(log.getDocument().getLength());
        };
        if (SwingUtilities.isEventDispatchThread()) {
            append.run();
        } else {
            SwingUtilities.invokeLater(append);
        }
    }

    private static String message(Exception exception) {
        Throwable cause = exception;
        while ((cause instanceof java.util.concurrent.ExecutionException
            || cause instanceof java.util.concurrent.CompletionException)
            && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String value = cause.getMessage();
        return value == null || value.isBlank() ? cause.getClass().getSimpleName() : value;
    }

    private record LauncherContext(Path dataDirectory, LauncherSettings settings) { }

    private record LoadedContext(LauncherSettings settings, Optional<MinecraftAccount> account) { }

    @FunctionalInterface
    private interface ContextOperation {
        String call(LauncherContext context) throws Exception;
    }
}
