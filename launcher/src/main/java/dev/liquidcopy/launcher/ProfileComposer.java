package dev.liquidcopy.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Objects;

/** Builds the standalone launcher profile from Mojang's named 1.21.11 profile. */
public final class ProfileComposer {
    public static final String BASE_PROFILE_ID = "1.21.11_unobfuscated";
    public static final String BASE_PROFILE_DIRECTORY = "1_21_11_unobfuscated";
    public static final String CUSTOM_VERSION_ID = "LiquidCopy-1.21.11";
    public static final String LIBRARY_GROUP = "dev.liquidcopy";
    public static final String LIBRARY_ARTIFACT = "liquidcopy-bootstrap";

    private final String bootstrapVersion;

    public ProfileComposer(String bootstrapVersion) {
        this.bootstrapVersion = requireSegment(bootstrapVersion, "bootstrapVersion");
    }

    public BootstrapArtifact bootstrapArtifact(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0) {
            throw new IllegalArgumentException("Bootstrap payload is empty");
        }
        return new BootstrapArtifact(libraryPath(), Hashing.sha1(payload), payload.length);
    }

    public JsonObject compose(JsonObject baseProfile, BootstrapArtifact bootstrap) {
        validateBase(baseProfile);
        Objects.requireNonNull(bootstrap, "bootstrap");
        if (!libraryPath().equals(bootstrap.relativePath())) {
            throw new IllegalArgumentException("Bootstrap path does not match launcher coordinates");
        }

        JsonObject custom = baseProfile.deepCopy();
        custom.addProperty("id", CUSTOM_VERSION_ID);
        custom.addProperty("name", "LiquidCopy 1.21.11");
        custom.addProperty("type", "release");

        JsonArray libraries = custom.has("libraries") && custom.get("libraries").isJsonArray()
            ? custom.getAsJsonArray("libraries")
            : new JsonArray();
        custom.add("libraries", libraries);
        libraries.add(libraryDescriptor(bootstrap));

        JsonObject arguments = custom.has("arguments") && custom.get("arguments").isJsonObject()
            ? custom.getAsJsonObject("arguments")
            : new JsonObject();
        custom.add("arguments", arguments);
        JsonArray inheritedJvm = arguments.has("jvm") && arguments.get("jvm").isJsonArray()
            ? arguments.getAsJsonArray("jvm")
            : new JsonArray();
        JsonArray jvm = new JsonArray();
        jvm.add(javaAgentArgument());
        inheritedJvm.forEach(jvm::add);
        arguments.add("jvm", jvm);
        return custom;
    }

    public void validateBase(JsonObject baseProfile) {
        Objects.requireNonNull(baseProfile, "baseProfile");
        String id = requiredString(baseProfile, "id");
        if (!BASE_PROFILE_ID.equals(id)) {
            throw new IllegalArgumentException("Expected Mojang profile " + BASE_PROFILE_ID + ", got " + id);
        }
        if (!baseProfile.has("downloads") || !baseProfile.get("downloads").isJsonObject()) {
            throw new IllegalArgumentException("Mojang profile has no downloads object");
        }
        if (!baseProfile.has("mainClass") || !"net.minecraft.client.main.Main".equals(requiredString(baseProfile, "mainClass"))) {
            throw new IllegalArgumentException("Mojang profile has an unexpected main class");
        }
    }

    public String libraryCoordinates() {
        return LIBRARY_GROUP + ':' + LIBRARY_ARTIFACT + ':' + bootstrapVersion;
    }

    public String libraryPath() {
        return LIBRARY_GROUP.replace('.', '/') + '/' + LIBRARY_ARTIFACT + '/' + bootstrapVersion + '/'
            + LIBRARY_ARTIFACT + '-' + bootstrapVersion + ".jar";
    }

    public String javaAgentArgument() {
        return "-javaagent:${library_directory}/" + libraryPath();
    }

    private JsonObject libraryDescriptor(BootstrapArtifact bootstrap) {
        JsonObject artifact = new JsonObject();
        artifact.addProperty("path", bootstrap.relativePath());
        artifact.addProperty("sha1", bootstrap.sha1());
        artifact.addProperty("size", bootstrap.size());

        JsonObject downloads = new JsonObject();
        downloads.add("artifact", artifact);

        JsonObject library = new JsonObject();
        library.addProperty("name", libraryCoordinates());
        library.add("downloads", downloads);
        return library;
    }

    static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing string field " + field);
        }
        return value.getAsString();
    }

    private static String requireSegment(String value, String label) {
        Objects.requireNonNull(value, label);
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
        return trimmed;
    }

    public record BootstrapArtifact(String relativePath, String sha1, long size) {
        public BootstrapArtifact {
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(sha1, "sha1");
            if (relativePath.startsWith("/") || relativePath.contains("..") || relativePath.contains("\\")) {
                throw new IllegalArgumentException("Unsafe bootstrap path " + relativePath);
            }
            if (!sha1.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("Invalid SHA-1 " + sha1);
            }
            if (size <= 0) {
                throw new IllegalArgumentException("Bootstrap size must be positive");
            }
        }
    }
}
