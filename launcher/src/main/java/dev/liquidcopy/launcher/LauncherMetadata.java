package dev.liquidcopy.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class LauncherMetadata {
    private static final String RESOURCE = "/liquidcopy-launcher.properties";

    private LauncherMetadata() {
    }

    static String bootstrapVersion() {
        Properties properties = new Properties();
        try (InputStream stream = LauncherMetadata.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing launcher metadata resource " + RESOURCE);
            }
            properties.load(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load launcher metadata", exception);
        }
        String version = properties.getProperty("bootstrap.version", "").trim();
        if (version.isEmpty()) {
            throw new IllegalStateException("bootstrap.version is empty");
        }
        return version;
    }
}
