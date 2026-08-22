package dev.liquidcopy.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.liquidcopy.api.module.Module;
import dev.liquidcopy.api.module.ModuleContext;
import dev.liquidcopy.api.module.ModuleRegistry;
import dev.liquidcopy.api.setting.Setting;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;

public final class ConfigStore {
    public static final int SCHEMA_VERSION = 1;
    private final Path path;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** Last successfully parsed document, retained so add-on keys survive a round trip. */
    private JsonObject retainedRoot = new JsonObject();

    public ConfigStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    public Path path() {
        return path;
    }

    public LoadResult load(ModuleRegistry registry, ModuleContext context) throws IOException {
        Objects.requireNonNull(registry, "registry");
        if (!Files.exists(path)) {
            registry.enableDefaults(context);
            save(registry);
            return new LoadResult(false, 0, 0);
        }

        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        int schema = root.has("schema") ? root.get("schema").getAsInt() : 0;
        if (schema > SCHEMA_VERSION) {
            throw new IOException("Profile schema " + schema + " is newer than supported " + SCHEMA_VERSION);
        }
        retainedRoot = root.deepCopy();

        JsonObject moduleStates = root.has("modules") && root.get("modules").isJsonObject()
            ? root.getAsJsonObject("modules") : new JsonObject();
        int loadedModules = 0;
        int loadedSettings = 0;

        for (Module module : registry.all()) {
            JsonElement moduleElement = moduleStates.get(module.id());
            if (moduleElement == null || !moduleElement.isJsonObject()) {
                if (module.metadata().defaultEnabled()) {
                    registry.setEnabled(module.id(), true, context);
                }
                continue;
            }
            JsonObject moduleObject = moduleElement.getAsJsonObject();
            JsonObject settingValues = moduleObject.has("settings") && moduleObject.get("settings").isJsonObject()
                ? moduleObject.getAsJsonObject("settings") : new JsonObject();
            for (Setting<?> setting : module.settings()) {
                JsonElement value = settingValues.get(setting.key());
                if (value != null) {
                    setting.fromJson(value);
                    loadedSettings++;
                }
            }
            boolean enabled = moduleObject.has("enabled") && moduleObject.get("enabled").getAsBoolean();
            registry.setEnabled(module.id(), enabled, context);
            loadedModules++;
        }
        return new LoadResult(true, loadedModules, loadedSettings);
    }

    public void save(ModuleRegistry registry) throws IOException {
        Objects.requireNonNull(registry, "registry");
        JsonObject root = retainedRoot.deepCopy();
        root.addProperty("schema", SCHEMA_VERSION);
        root.addProperty("savedAt", Instant.now().toString());
        JsonObject moduleStates = root.has("modules") && root.get("modules").isJsonObject()
            ? root.getAsJsonObject("modules") : new JsonObject();
        for (Module module : registry.all()) {
            JsonObject moduleObject = moduleStates.has(module.id()) && moduleStates.get(module.id()).isJsonObject()
                ? moduleStates.getAsJsonObject(module.id()) : new JsonObject();
            moduleObject.addProperty("enabled", module.isEnabled());
            JsonObject settings = moduleObject.has("settings") && moduleObject.get("settings").isJsonObject()
                ? moduleObject.getAsJsonObject("settings") : new JsonObject();
            for (Setting<?> setting : module.settings()) {
                settings.add(setting.key(), setting.toJson());
            }
            moduleObject.add("settings", settings);
            moduleStates.add(module.id(), moduleObject);
        }
        root.add("modules", moduleStates);
        retainedRoot = root.deepCopy();

        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, gson.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record LoadResult(boolean existingProfile, int modulesLoaded, int settingsLoaded) {
    }
}
