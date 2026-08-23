package dev.liquidcopy.launcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
/** Non-secret launcher settings stored inside the selected LiquidCopy data root. */
record LauncherSettings(int maxMemoryMiB) {
    static final String FILE_NAME = "launcher-settings.json";
    static final int DEFAULT_MEMORY_MIB = 4_096;
    static final int MIN_MEMORY_MIB = 1_024;
    static final int MAX_MEMORY_MIB = 32_768;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    LauncherSettings {
        if (maxMemoryMiB < MIN_MEMORY_MIB || maxMemoryMiB > MAX_MEMORY_MIB) {
            throw new IllegalArgumentException("Memory must be between " + MIN_MEMORY_MIB + " and "
                + MAX_MEMORY_MIB + " MiB");
        }
    }

    static LauncherSettings defaults() {
        return new LauncherSettings(DEFAULT_MEMORY_MIB);
    }

    static LauncherSettings load(Path dataDirectory) throws IOException {
        Path file = settingsFile(dataDirectory);
        if (!Files.isRegularFile(file)) {
            return defaults();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            int memory = root.has("maxMemoryMiB") ? root.get("maxMemoryMiB").getAsInt() : DEFAULT_MEMORY_MIB;
            return new LauncherSettings(memory);
        } catch (RuntimeException exception) {
            throw new IOException("Unable to read " + file + ": " + exception.getMessage(), exception);
        }
    }

    void save(Path dataDirectory) throws IOException {
        Path target = settingsFile(dataDirectory);
        Files.createDirectories(target.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("maxMemoryMiB", maxMemoryMiB);
        byte[] bytes = (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(target.getParent(), ".launcher-settings", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static Path settingsFile(Path dataDirectory) {
        return dataDirectory.toAbsolutePath().normalize().resolve(FILE_NAME);
    }
}
