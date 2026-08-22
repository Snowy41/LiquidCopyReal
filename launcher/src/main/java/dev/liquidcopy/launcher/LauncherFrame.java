package dev.liquidcopy.launcher;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;

final class LauncherFrame extends JFrame {
    private final InstallService installService;
    private final LauncherOpener opener;
    private final JTextField directory = new JTextField(MinecraftDirectories.defaultDirectory().toString(), 42);
    private final JTextArea status = new JTextArea();
    private final JButton install = new JButton("Install / Update");
    private final JButton verify = new JButton("Verify");
    private final JButton browse = new JButton("Browse…");
    private final JButton openLauncher = new JButton("Open Minecraft Launcher");
    private final JButton openInstance = new JButton("Open instance folder");

    LauncherFrame(InstallService installService) {
        this(installService, LauncherOpener.system());
    }

    LauncherFrame(InstallService installService, LauncherOpener opener) {
        super("LiquidCopy 1.21.11 Launcher");
        this.installService = Objects.requireNonNull(installService, "installService");
        this.opener = Objects.requireNonNull(opener, "opener");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(720, 420));
        setLocationByPlatform(true);
        buildUi();
        pack();
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel location = new JPanel(new BorderLayout(8, 8));
        location.add(new JLabel("Minecraft directory"), BorderLayout.NORTH);
        location.add(directory, BorderLayout.CENTER);
        location.add(browse, BorderLayout.EAST);
        root.add(location, BorderLayout.NORTH);

        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setText("Installs a standalone LiquidCopy-1.21.11 version based on Mojang's official named 1.21.11 client.\n");
        root.add(new JScrollPane(status), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(openInstance);
        buttons.add(openLauncher);
        buttons.add(verify);
        buttons.add(install);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);

        browse.addActionListener(event -> chooseDirectory());
        install.addActionListener(event -> execute("Install", () -> {
            InstallService.InstallReport report = installService.install(selectedDirectory());
            return "Installed " + ProfileComposer.CUSTOM_VERSION_ID + " in " + report.minecraftDirectory() + "\n"
                + String.join("\n", report.messages())
                + "\n\nRestart the Minecraft Launcher and select LiquidCopy 1.21.11.";
        }));
        verify.addActionListener(event -> execute("Verify", () -> {
            InstallService.VerificationReport report = installService.verify(selectedDirectory());
            return (report.valid() ? "Verification succeeded" : "Verification failed") + "\n"
                + String.join("\n", report.messages());
        }));
        openLauncher.addActionListener(event -> execute("Open Minecraft Launcher", () -> {
            opener.openMinecraftLauncher();
            return "Minecraft Launcher open request sent.";
        }));
        openInstance.addActionListener(event -> execute("Open instance folder", () -> {
            Path instance = InstallService.instanceDirectory(selectedDirectory());
            opener.openInstanceDirectory(selectedDirectory());
            return "Opened " + instance;
        }));
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser(selectedDirectory().toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Minecraft directory");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            directory.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
        }
    }

    private Path selectedDirectory() {
        return Path.of(directory.getText().trim()).toAbsolutePath().normalize();
    }

    private void execute(String label, Callable<String> operation) {
        setBusy(true);
        status.append("\n" + label + " started…\n");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return operation.call();
            }

            @Override
            protected void done() {
                try {
                    status.append(get() + "\n");
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    status.append(label + " failed: " + cause.getMessage() + "\n");
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private void setBusy(boolean busy) {
        install.setEnabled(!busy);
        verify.setEnabled(!busy);
        browse.setEnabled(!busy);
        openLauncher.setEnabled(!busy);
        openInstance.setEnabled(!busy);
        directory.setEnabled(!busy);
    }
}
