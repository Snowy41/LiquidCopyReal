package dev.liquidcopy.launcher;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/** Opens the operating system's default browser; injectable for deterministic tests. */
@FunctionalInterface
public interface SystemBrowser {
    void open(URI uri) throws IOException;

    static SystemBrowser desktop() {
        return uri -> {
            IOException desktopFailure = null;
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                try {
                    Desktop.getDesktop().browse(uri);
                    return;
                } catch (IOException exception) {
                    desktopFailure = exception;
                }
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            try {
                Process process;
            if (os.startsWith("windows")) {
                    process = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toASCIIString()).start();
                } else if (os.contains("mac")) {
                    process = new ProcessBuilder("open", uri.toASCIIString()).start();
                } else {
                    process = new ProcessBuilder("xdg-open", uri.toASCIIString()).start();
                }
                if (!process.isAlive() && process.exitValue() != 0) {
                    throw new IOException("The operating system did not open the browser (exit "
                        + process.exitValue() + ")");
                }
            } catch (IOException fallbackFailure) {
                if (desktopFailure != null) {
                    fallbackFailure.addSuppressed(desktopFailure);
                }
                throw fallbackFailure;
            }
        };
    }
}
