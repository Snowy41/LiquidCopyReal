package dev.liquidcopy.launcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs and verifies the standalone named client metadata plus the embedded LiquidCopy agent. */
public final class InstallService {
    public static final URI OFFICIAL_PROFILE_ZIP = URI.create(
        "https://piston-data.mojang.com/v1/objects/e11114e8a2eea43bac93c022c9327d3916b24738/1_21_11_unobfuscated.zip"
    );
    public static final String OFFICIAL_PROFILE_ZIP_SHA1 = "e11114e8a2eea43bac93c022c9327d3916b24738";
    public static final long OFFICIAL_PROFILE_ZIP_SIZE = 7_559L;
    public static final String OFFICIAL_NAMED_CLIENT_SHA1 = "4509ee9b65f226be61142d37bf05f8d28b03417b";
    public static final String INSTANCE_DIRECTORY = "instances/LiquidCopy-1.21.11";
    private static final String PROFILE_ENTRY = ProfileComposer.BASE_PROFILE_DIRECTORY + '/'
        + ProfileComposer.BASE_PROFILE_DIRECTORY + ".json";
    private static final int MAX_PROFILE_JSON_BYTES = 1_000_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final ResourceFetcher fetcher;
    private final PayloadSource payloadSource;
    private final ProfileComposer composer;
    private final URI profileZipUri;
    private final String profileZipSha1;

    public InstallService() {
        this(httpFetcher(), embeddedPayload(), LauncherMetadata.bootstrapVersion(),
            OFFICIAL_PROFILE_ZIP, OFFICIAL_PROFILE_ZIP_SHA1);
    }

    public InstallService(
        ResourceFetcher fetcher,
        PayloadSource payloadSource,
        String bootstrapVersion,
        URI profileZipUri,
        String profileZipSha1
    ) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.payloadSource = Objects.requireNonNull(payloadSource, "payloadSource");
        this.composer = new ProfileComposer(bootstrapVersion);
        this.profileZipUri = Objects.requireNonNull(profileZipUri, "profileZipUri");
        this.profileZipSha1 = Objects.requireNonNull(profileZipSha1, "profileZipSha1");
        if (!profileZipSha1.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Invalid profile ZIP SHA-1");
        }
    }

    public InstallReport install(Path minecraftDirectory) throws IOException, InterruptedException {
        Path root = normalizeRoot(minecraftDirectory);
        Files.createDirectories(root);

        byte[] profileZip = fetcher.fetch(profileZipUri);
        verifyHash("official profile ZIP", profileZip, profileZipSha1);
        byte[] baseProfileBytes = extractBaseProfile(profileZip);
        JsonObject baseProfile = parseObject(baseProfileBytes, "official profile");
        composer.validateBase(baseProfile);
        requireNamedClient(baseProfile);

        byte[] bootstrapBytes = payloadSource.read();
        ProfileComposer.BootstrapArtifact bootstrap = composer.bootstrapArtifact(bootstrapBytes);
        JsonObject customProfile = composer.compose(baseProfile, bootstrap);

        Path baseProfilePath = root.resolve("versions").resolve(ProfileComposer.BASE_PROFILE_DIRECTORY)
            .resolve(ProfileComposer.BASE_PROFILE_DIRECTORY + ".json");
        Path customProfilePath = root.resolve("versions").resolve(ProfileComposer.CUSTOM_VERSION_ID)
            .resolve(ProfileComposer.CUSTOM_VERSION_ID + ".json");
        Path bootstrapPath = resolveRelative(root.resolve("libraries"), bootstrap.relativePath());

        atomicWrite(baseProfilePath, prettyJson(baseProfile));
        atomicWrite(bootstrapPath, bootstrapBytes);
        atomicWrite(customProfilePath, prettyJson(customProfile));
        Path instance = instanceDirectory(root);
        Files.createDirectories(instance);

        VerificationReport verification = verify(root);
        if (!verification.valid()) {
            throw new IOException("Installed files did not verify: " + String.join("; ", verification.messages()));
        }
        return new InstallReport(root, baseProfilePath, customProfilePath, bootstrapPath, instance,
            verification.messages());
    }

    public VerificationReport verify(Path minecraftDirectory) {
        List<String> messages = new ArrayList<>();
        Path root;
        try {
            root = normalizeRoot(minecraftDirectory);
        } catch (RuntimeException exception) {
            return new VerificationReport(false, List.of(exception.getMessage()));
        }

        Path basePath = root.resolve("versions").resolve(ProfileComposer.BASE_PROFILE_DIRECTORY)
            .resolve(ProfileComposer.BASE_PROFILE_DIRECTORY + ".json");
        Path customPath = root.resolve("versions").resolve(ProfileComposer.CUSTOM_VERSION_ID)
            .resolve(ProfileComposer.CUSTOM_VERSION_ID + ".json");
        boolean valid = true;
        try {
            JsonObject base = readObject(basePath, "official named profile");
            composer.validateBase(base);
            requireNamedClient(base);
            messages.add("Official named profile: OK");
        } catch (Exception exception) {
            valid = false;
            messages.add("Official named profile: " + exception.getMessage());
        }

        try {
            JsonObject custom = readObject(customPath, "LiquidCopy profile");
            if (!ProfileComposer.CUSTOM_VERSION_ID.equals(ProfileComposer.requiredString(custom, "id"))) {
                throw new IOException("wrong id");
            }
            requireNamedClient(custom);
            ProfileComposer.BootstrapArtifact descriptor = bootstrapDescriptor(custom);
            Path bootstrapPath = resolveRelative(root.resolve("libraries"), descriptor.relativePath());
            byte[] payload = Files.readAllBytes(bootstrapPath);
            verifyHash("bootstrap payload", payload, descriptor.sha1());
            if (payload.length != descriptor.size()) {
                throw new IOException("bootstrap size mismatch");
            }
            if (!containsJvmArgument(custom, composer.javaAgentArgument())) {
                throw new IOException("javaagent argument is missing");
            }
            messages.add("LiquidCopy version and bootstrap: OK");
        } catch (Exception exception) {
            valid = false;
            messages.add("LiquidCopy version and bootstrap: " + exception.getMessage());
        }

        try {
            if (!Files.isDirectory(instanceDirectory(root))) {
                throw new IOException("game directory is missing");
            }
            messages.add("Standalone game directory: OK");
        } catch (Exception exception) {
            valid = false;
            messages.add("Standalone game directory: " + exception.getMessage());
        }
        return new VerificationReport(valid, List.copyOf(messages));
    }

    private ProfileComposer.BootstrapArtifact bootstrapDescriptor(JsonObject custom) throws IOException {
        JsonArray libraries = custom.getAsJsonArray("libraries");
        if (libraries == null) {
            throw new IOException("libraries array is missing");
        }
        for (JsonElement element : libraries) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject library = element.getAsJsonObject();
            if (!composer.libraryCoordinates().equals(stringOrNull(library, "name"))) {
                continue;
            }
            JsonObject downloads = library.getAsJsonObject("downloads");
            JsonObject artifact = downloads == null ? null : downloads.getAsJsonObject("artifact");
            if (artifact == null) {
                throw new IOException("bootstrap download descriptor is missing");
            }
            try {
                return new ProfileComposer.BootstrapArtifact(
                    ProfileComposer.requiredString(artifact, "path"),
                    ProfileComposer.requiredString(artifact, "sha1"),
                    artifact.get("size").getAsLong()
                );
            } catch (RuntimeException exception) {
                throw new IOException("invalid bootstrap descriptor", exception);
            }
        }
        throw new IOException("bootstrap library is missing");
    }

    private static boolean containsJvmArgument(JsonObject profile, String expected) {
        JsonObject arguments = profile.getAsJsonObject("arguments");
        JsonArray jvm = arguments == null ? null : arguments.getAsJsonArray("jvm");
        if (jvm == null) {
            return false;
        }
        for (JsonElement value : jvm) {
            if (value.isJsonPrimitive() && expected.equals(value.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static void requireNamedClient(JsonObject profile) throws IOException {
        try {
            JsonObject downloads = profile.getAsJsonObject("downloads");
            JsonObject client = downloads == null ? null : downloads.getAsJsonObject("client");
            if (client == null || !OFFICIAL_NAMED_CLIENT_SHA1.equals(ProfileComposer.requiredString(client, "sha1"))) {
                throw new IOException("profile does not target Mojang's named 1.21.11 client");
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid client download descriptor", exception);
        }
    }

    private static byte[] extractBaseProfile(byte[] zipBytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals("..")) {
                    throw new IOException("Unsafe ZIP entry " + entry.getName());
                }
                if (PROFILE_ENTRY.equals(name)) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8_192];
                    int total = 0;
                    for (int count; (count = zip.read(buffer)) >= 0; ) {
                        total += count;
                        if (total > MAX_PROFILE_JSON_BYTES) {
                            throw new IOException("Official profile JSON is unexpectedly large");
                        }
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
            }
        }
        throw new IOException("Official ZIP does not contain " + PROFILE_ENTRY);
    }

    private static JsonObject readObject(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException(label + " is missing at " + path);
        }
        return parseObject(Files.readAllBytes(path), label);
    }

    private static JsonObject parseObject(byte[] bytes, String label) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException(label + " is not a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Unable to parse " + label, exception);
        }
    }

    private static byte[] prettyJson(JsonObject value) {
        return (GSON.toJson(value) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Target has no parent: " + target);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "." + target.getFileName(), ".tmp");
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

    private static Path resolveRelative(Path root, String relative) throws IOException {
        Path resolved = root.resolve(relative.replace('/', java.io.File.separatorChar)).toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IOException("Path leaves Minecraft libraries directory: " + relative);
        }
        return resolved;
    }

    private static Path normalizeRoot(Path root) {
        Objects.requireNonNull(root, "minecraftDirectory");
        return root.toAbsolutePath().normalize();
    }

    public static Path instanceDirectory(Path minecraftDirectory) {
        return normalizeRoot(minecraftDirectory).resolve(INSTANCE_DIRECTORY.replace('/', java.io.File.separatorChar));
    }

    private static void verifyHash(String label, byte[] bytes, String expected) throws IOException {
        String actual = Hashing.sha1(bytes);
        if (!expected.equals(actual)) {
            throw new IOException(label + " SHA-1 mismatch: expected " + expected + ", got " + actual);
        }
    }

    private static String stringOrNull(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static ResourceFetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        return uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri).header("User-Agent", "LiquidCopy-Launcher/1.21.11").GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("Download failed with HTTP " + response.statusCode() + " for " + uri);
            }
            return response.body();
        };
    }

    private static PayloadSource embeddedPayload() {
        return () -> {
            try (InputStream stream = InstallService.class.getResourceAsStream("/payload/liquidcopy-bootstrap.jar")) {
                if (stream == null) {
                    throw new IOException("Launcher does not contain /payload/liquidcopy-bootstrap.jar");
                }
                return stream.readAllBytes();
            }
        };
    }

    @FunctionalInterface
    public interface ResourceFetcher {
        byte[] fetch(URI uri) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface PayloadSource {
        byte[] read() throws IOException;
    }

    public record InstallReport(
        Path dataDirectory,
        Path baseProfile,
        Path customProfile,
        Path bootstrap,
        Path instanceDirectory,
        List<String> messages
    ) {
    }

    public record VerificationReport(boolean valid, List<String> messages) {
    }
}
