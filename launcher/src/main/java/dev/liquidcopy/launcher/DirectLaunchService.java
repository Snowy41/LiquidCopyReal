package dev.liquidcopy.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
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
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves and starts the named Minecraft client directly, without invoking or
 * depending on the official Minecraft Launcher.
 */
public final class DirectLaunchService {
    private static final String VERSION_ID = ProfileComposer.CUSTOM_VERSION_ID;
    private static final String VERSION_JSON = VERSION_ID + ".json";
    private static final String VERSION_JAR = VERSION_ID + ".jar";
    private static final String MAIN_CLASS = "net.minecraft.client.main.Main";
    private static final URI ASSET_OBJECT_ROOT = URI.create("https://resources.download.minecraft.net/");
    private static final int MAX_VERSION_JSON_BYTES = 2_000_000;
    private static final int MAX_ASSET_INDEX_BYTES = 16_000_000;
    private static final long MAX_NATIVE_ENTRY_BYTES = 128L * 1024 * 1024;
    private static final long MAX_NATIVE_TOTAL_BYTES = 512L * 1024 * 1024;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern JAVA_VERSION = Pattern.compile("(?:version \")?(\\d+)(?:[._][0-9]+)*");

    private final ArtifactDownloader downloader;
    private final RuntimePlatform platform;
    private final ProcessStarter processStarter;
    private final JavaInspector javaInspector;
    private final String expectedClientSha1;

    public DirectLaunchService() {
        this(httpDownloader(), RuntimePlatform.current(), DirectLaunchService::startProcess,
            DirectLaunchService::inspectJava, InstallService.OFFICIAL_NAMED_CLIENT_SHA1);
    }

    DirectLaunchService(
        ArtifactDownloader downloader,
        RuntimePlatform platform,
        ProcessStarter processStarter,
        JavaInspector javaInspector
    ) {
        this(downloader, platform, processStarter, javaInspector, null);
    }

    DirectLaunchService(
        ArtifactDownloader downloader,
        RuntimePlatform platform,
        ProcessStarter processStarter,
        JavaInspector javaInspector,
        String expectedClientSha1
    ) {
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.processStarter = Objects.requireNonNull(processStarter, "processStarter");
        this.javaInspector = Objects.requireNonNull(javaInspector, "javaInspector");
        this.expectedClientSha1 = expectedClientSha1;
    }

    public PreparedLaunch prepare(
        Path dataDirectory,
        AuthenticatedAccount account,
        LaunchOptions options,
        ProgressListener progress
    ) throws IOException, InterruptedException {
        Path root = normalizeRoot(dataDirectory);
        Objects.requireNonNull(account, "account");
        options = options == null ? LaunchOptions.defaults() : options;
        progress = progress == null ? ProgressListener.NONE : progress;
        Files.createDirectories(root);

        progress.update(new Progress("java", "Checking Java 21", 0, 1));
        JavaRuntime javaRuntime = javaInspector.inspect(options.javaExecutable());
        if (javaRuntime.majorVersion() != 21) {
            throw new IOException("Minecraft 1.21.11 requires Java 21; " + options.javaExecutable()
                + " reported Java " + javaRuntime.majorVersion());
        }
        progress.update(new Progress("java", "Java 21", 1, 1));

        Path versionDirectory = root.resolve("versions").resolve(VERSION_ID);
        Path versionJsonPath = versionDirectory.resolve(VERSION_JSON);
        JsonObject profile = readObject(versionJsonPath, "LiquidCopy version JSON", MAX_VERSION_JSON_BYTES);
        validateProfile(profile);
        Map<String, Boolean> features = featureFlags(options);
        JsonArray libraries = requiredArray(profile, "libraries");
        validateNativePlatformSupport(libraries, features);

        Path librariesDirectory = root.resolve("libraries");
        Path assetsDirectory = root.resolve("assets");
        Path gameDirectory = InstallService.instanceDirectory(root);
        Files.createDirectories(librariesDirectory);
        Files.createDirectories(assetsDirectory);
        Files.createDirectories(gameDirectory);

        AtomicInteger downloadedFiles = new AtomicInteger();
        java.util.concurrent.atomic.AtomicLong downloadedBytes = new java.util.concurrent.atomic.AtomicLong();

        Artifact client = artifact(profile.getAsJsonObject("downloads"), "client", "Minecraft client");
        Path clientJar = versionDirectory.resolve(VERSION_JAR);
        ensureArtifact(client, clientJar, progress, "client", 1, 1, downloadedFiles, downloadedBytes);

        List<Path> classpath = new ArrayList<>();
        List<NativeLibrary> nativeLibraries = new ArrayList<>();
        Set<Path> resolvedLibraries = new HashSet<>();
        List<ResolvedArtifact> libraryDownloads = new ArrayList<>();

        for (JsonElement element : libraries) {
            if (!element.isJsonObject()) {
                throw new IOException("libraries contains a non-object entry");
            }
            JsonObject library = element.getAsJsonObject();
            if (!profileRulesAllow(library.get("rules"), features)) {
                continue;
            }
            String libraryName = optionalString(library, "name", "unnamed library");
            String libraryClassifier = mavenClassifier(libraryName);
            if (!artifactClassifierMatches(libraryClassifier, platform)) {
                continue;
            }
            JsonObject downloads = library.has("downloads") && library.get("downloads").isJsonObject()
                ? library.getAsJsonObject("downloads") : null;
            if (downloads == null) {
                throw new IOException("Library " + libraryName + " has no downloads object");
            }

            JsonObject artifactObject = objectOrNull(downloads, "artifact");
            if (artifactObject != null) {
                Artifact libraryArtifact = artifact(artifactObject, libraryName);
                Path target = resolveInside(librariesDirectory, libraryArtifact.path(), "library artifact");
                if (resolvedLibraries.add(target)) {
                    libraryDownloads.add(new ResolvedArtifact(libraryArtifact, target, libraryName));
                    classpath.add(target);
                }
                if (isNativeClassifier(libraryClassifier)) {
                    nativeLibraries.add(new NativeLibrary(target, extractionExcludes(library)));
                }
            }

            JsonObject natives = objectOrNull(library, "natives");
            JsonObject classifiers = objectOrNull(downloads, "classifiers");
            if (natives != null && classifiers != null && natives.has(platform.osName())) {
                String classifierTemplate = requiredString(natives, platform.osName());
                String classifier = classifierTemplate.replace("${arch}", platform.archBits());
                JsonObject classifierObject = objectOrNull(classifiers, classifier);
                if (classifierObject == null) {
                    throw new IOException("Library " + libraryName + " has no native classifier " + classifier);
                }
                Artifact nativeArtifact = artifact(classifierObject, libraryName + ':' + classifier);
                Path target = resolveInside(librariesDirectory, nativeArtifact.path(), "native library");
                if (resolvedLibraries.add(target)) {
                    libraryDownloads.add(new ResolvedArtifact(nativeArtifact, target, libraryName + ':' + classifier));
                }
                nativeLibraries.add(new NativeLibrary(target, extractionExcludes(library)));
            }
        }

        downloadBatch(libraryDownloads, progress, "libraries", downloadedFiles, downloadedBytes);
        classpath.add(clientJar);

        JsonObject assetIndexDescriptor = requiredObject(profile, "assetIndex");
        String assetIndexId = requiredString(assetIndexDescriptor, "id");
        Artifact assetIndexArtifact = artifact(assetIndexDescriptor, "asset index");
        Path assetIndexPath = resolveInside(assetsDirectory.resolve("indexes"), assetIndexId + ".json", "asset index");
        ensureArtifact(assetIndexArtifact, assetIndexPath, progress, "asset-index", 1, 1,
            downloadedFiles, downloadedBytes);
        JsonObject assetIndex = readObject(assetIndexPath, "asset index", MAX_ASSET_INDEX_BYTES);
        List<ResolvedArtifact> assets = collectAssets(assetIndex, assetsDirectory);
        downloadBatch(assets, progress, "assets", downloadedFiles, downloadedBytes);

        Path loggingConfiguration = downloadLogging(profile, assetsDirectory, progress,
            downloadedFiles, downloadedBytes);

        String profileHash = sha1(versionJsonPath).substring(0, 12);
        Path nativesDirectory = root.resolve("runtime").resolve("natives").resolve(VERSION_ID)
            .resolve(profileHash + '-' + platform.osName() + '-' + platform.architecture().id());
        Files.createDirectories(nativesDirectory);
        progress.update(new Progress("natives", "Extracting native libraries", 0, nativeLibraries.size()));
        extractNatives(nativeLibraries, nativesDirectory);
        progress.update(new Progress("natives", "Native libraries ready", nativeLibraries.size(),
            nativeLibraries.size()));

        LinkedHashMap<String, String> substitutions = substitutions(root, librariesDirectory, assetsDirectory,
            gameDirectory, nativesDirectory, classpath, profile, assetIndexId, account, options);

        List<String> command = new ArrayList<>();
        command.add(options.javaExecutable().toString());
        command.add("-Xms" + options.minMemoryMiB() + "M");
        command.add("-Xmx" + options.maxMemoryMiB() + "M");
        command.addAll(options.extraJvmArguments());
        JsonObject arguments = requiredObject(profile, "arguments");
        command.addAll(resolveArguments(requiredArray(arguments, "jvm"), substitutions, features));
        if (loggingConfiguration != null) {
            JsonObject logging = requiredObject(requiredObject(profile, "logging"), "client");
            String loggingArgument = requiredString(logging, "argument")
                .replace("${path}", loggingConfiguration.toString());
            command.add(loggingArgument);
        }
        command.add(MAIN_CLASS);
        command.addAll(resolveArguments(requiredArray(arguments, "game"), substitutions, features));

        ensureNoPlaceholders(command);
        requireLiquidCopyAgent(command, librariesDirectory);
        Path logFile = root.resolve("logs").resolve("latest.log");
        Files.createDirectories(logFile.getParent());
        progress.update(new Progress("ready", "Minecraft 1.21.11 is ready", 1, 1));
        return new PreparedLaunch(List.copyOf(command), gameDirectory, nativesDirectory, logFile,
            downloadedFiles.get(), downloadedBytes.get());
    }

    public PreparedLaunch prepare(Path dataDirectory, AuthenticatedAccount account, LaunchOptions options)
        throws IOException, InterruptedException {
        return prepare(dataDirectory, account, options, ProgressListener.NONE);
    }

    public Process start(PreparedLaunch prepared) throws IOException {
        Objects.requireNonNull(prepared, "prepared");
        return processStarter.start(prepared.command(), prepared.workingDirectory(), prepared.logFile());
    }

    public LaunchResult launch(
        Path dataDirectory,
        AuthenticatedAccount account,
        LaunchOptions options,
        ProgressListener progress
    ) throws IOException, InterruptedException {
        PreparedLaunch prepared = prepare(dataDirectory, account, options, progress);
        return new LaunchResult(start(prepared), prepared);
    }

    public LaunchResult launch(Path dataDirectory, AuthenticatedAccount account, LaunchOptions options)
        throws IOException, InterruptedException {
        return launch(dataDirectory, account, options, ProgressListener.NONE);
    }

    private Path downloadLogging(
        JsonObject profile,
        Path assetsDirectory,
        ProgressListener progress,
        AtomicInteger downloadedFiles,
        java.util.concurrent.atomic.AtomicLong downloadedBytes
    ) throws IOException, InterruptedException {
        JsonObject loggingRoot = objectOrNull(profile, "logging");
        JsonObject clientLogging = loggingRoot == null ? null : objectOrNull(loggingRoot, "client");
        JsonObject file = clientLogging == null ? null : objectOrNull(clientLogging, "file");
        if (file == null) {
            return null;
        }
        String id = requiredString(file, "id");
        Artifact artifact = artifact(file, "logging configuration");
        Path target = resolveInside(assetsDirectory.resolve("log_configs"), id, "logging configuration");
        ensureArtifact(artifact, target, progress, "logging", 1, 1, downloadedFiles, downloadedBytes);
        return target;
    }

    private List<ResolvedArtifact> collectAssets(JsonObject index, Path assetsDirectory) throws IOException {
        JsonObject objects = requiredObject(index, "objects");
        Map<String, ResolvedArtifact> unique = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                throw new IOException("Asset " + entry.getKey() + " has a non-object descriptor");
            }
            JsonObject descriptor = entry.getValue().getAsJsonObject();
            String hash = requiredSha1(descriptor, "hash", "asset " + entry.getKey());
            long size = requiredNonNegativeLong(descriptor, "size", "asset " + entry.getKey());
            String relative = hash.substring(0, 2) + '/' + hash;
            URI uri = ASSET_OBJECT_ROOT.resolve(relative);
            Artifact artifact = new Artifact(relative, hash, size, uri);
            Path target = resolveInside(assetsDirectory.resolve("objects"), relative, "asset object");
            unique.putIfAbsent(hash, new ResolvedArtifact(artifact, target, entry.getKey()));
        }
        return List.copyOf(unique.values());
    }

    private void downloadBatch(
        List<ResolvedArtifact> artifacts,
        ProgressListener progress,
        String phase,
        AtomicInteger downloadedFiles,
        java.util.concurrent.atomic.AtomicLong downloadedBytes
    ) throws IOException, InterruptedException {
        if (artifacts.isEmpty()) {
            progress.update(new Progress(phase, "Nothing to download", 0, 0));
            return;
        }
        int workers = Math.min(8, artifacts.size());
        AtomicInteger completed = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "liquidcopy-download");
            thread.setDaemon(true);
            return thread;
        });
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (ResolvedArtifact resolved : artifacts) {
                futures.add(executor.submit(() -> {
                    ensureArtifact(resolved.artifact(), resolved.target(), ProgressListener.NONE, phase, 0, 0,
                        downloadedFiles, downloadedBytes);
                    int done = completed.incrementAndGet();
                    progress.update(new Progress(phase, resolved.label(), done, artifacts.size()));
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException exception) {
                    for (Future<?> pending : futures) {
                        pending.cancel(true);
                    }
                    Throwable cause = exception.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    if (cause instanceof InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                    throw new IOException("Unable to resolve " + phase, cause);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void ensureArtifact(
        Artifact artifact,
        Path target,
        ProgressListener progress,
        String phase,
        int completed,
        int total,
        AtomicInteger downloadedFiles,
        java.util.concurrent.atomic.AtomicLong downloadedBytes
    ) throws IOException, InterruptedException {
        if (isVerified(target, artifact)) {
            progress.update(new Progress(phase, target.getFileName().toString(), completed, total));
            return;
        }
        if (artifact.uri() == null) {
            throw new IOException(artifact.label() + " is missing or corrupt and has no download URL: " + target);
        }
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".download");
        try {
            downloader.download(artifact.uri(), temporary);
            verifyArtifact(temporary, artifact);
            moveReplace(temporary, target);
            downloadedFiles.incrementAndGet();
            downloadedBytes.addAndGet(Files.size(target));
        } finally {
            Files.deleteIfExists(temporary);
        }
        progress.update(new Progress(phase, target.getFileName().toString(), completed, total));
    }

    private static boolean isVerified(Path target, Artifact artifact) throws IOException {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        if (artifact.size() >= 0 && Files.size(target) != artifact.size()) {
            return false;
        }
        return artifact.sha1().equals(sha1(target));
    }

    private static void verifyArtifact(Path target, Artifact artifact) throws IOException {
        if (artifact.size() >= 0 && Files.size(target) != artifact.size()) {
            throw new IOException(artifact.label() + " size mismatch: expected " + artifact.size()
                + ", got " + Files.size(target));
        }
        String actual = sha1(target);
        if (!artifact.sha1().equals(actual)) {
            throw new IOException(artifact.label() + " SHA-1 mismatch: expected " + artifact.sha1()
                + ", got " + actual);
        }
    }

    private static void extractNatives(List<NativeLibrary> libraries, Path nativesDirectory) throws IOException {
        Set<String> extractedNames = new HashSet<>();
        long total = 0;
        for (NativeLibrary nativeLibrary : libraries) {
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(nativeLibrary.jar())))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                    String name = entry.getName().replace('\\', '/');
                    validateZipEntry(name);
                    if (entry.isDirectory() || excluded(name, nativeLibrary.excludes()) || !isNativeBinary(name)) {
                        continue;
                    }
                    long declaredSize = entry.getSize();
                    if (declaredSize > MAX_NATIVE_ENTRY_BYTES) {
                        throw new IOException("Native ZIP entry is too large: " + name);
                    }
                    String fileName = name.substring(name.lastIndexOf('/') + 1);
                    if (fileName.isBlank()) {
                        continue;
                    }
                    ByteArrayOutputStream output = new ByteArrayOutputStream(
                        declaredSize > 0 && declaredSize < Integer.MAX_VALUE ? (int) declaredSize : 8192);
                    byte[] buffer = new byte[8192];
                    long entryBytes = 0;
                    for (int count; (count = zip.read(buffer)) >= 0; ) {
                        entryBytes += count;
                        total += count;
                        if (entryBytes > MAX_NATIVE_ENTRY_BYTES || total > MAX_NATIVE_TOTAL_BYTES) {
                            throw new IOException("Native extraction limit exceeded at " + name);
                        }
                        output.write(buffer, 0, count);
                    }
                    Path target = nativesDirectory.resolve(fileName).toAbsolutePath().normalize();
                    if (!target.startsWith(nativesDirectory.toAbsolutePath().normalize())) {
                        throw new IOException("Native entry leaves extraction directory: " + name);
                    }
                    byte[] bytes = output.toByteArray();
                    if (!extractedNames.add(fileName.toLowerCase(Locale.ROOT)) && Files.exists(target)) {
                        byte[] prior = Files.readAllBytes(target);
                        if (!java.util.Arrays.equals(prior, bytes)) {
                            throw new IOException("Conflicting native library " + fileName);
                        }
                        continue;
                    }
                    atomicWrite(target, bytes);
                }
            }
        }
    }

    private static boolean excluded(String name, List<String> excludes) {
        for (String exclude : excludes) {
            String normalized = exclude.replace('\\', '/');
            if (name.startsWith(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static void validateZipEntry(String name) throws IOException {
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("Unsafe native ZIP entry " + name);
        }
        for (String segment : name.split("/")) {
            if ("..".equals(segment) || segment.indexOf(':') >= 0 || segment.indexOf('\0') >= 0) {
                throw new IOException("Unsafe native ZIP entry " + name);
            }
        }
    }

    private static boolean isNativeBinary(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".dll") || lower.endsWith(".so") || lower.contains(".so.")
            || lower.endsWith(".dylib") || lower.endsWith(".jnilib");
    }

    private static LinkedHashMap<String, String> substitutions(
        Path root,
        Path libraries,
        Path assets,
        Path game,
        Path natives,
        List<Path> classpath,
        JsonObject profile,
        String assetIndexId,
        AuthenticatedAccount account,
        LaunchOptions options
    ) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("auth_player_name", account.playerName());
        values.put("version_name", requiredStringUnchecked(profile, "id"));
        values.put("game_directory", game.toString());
        values.put("assets_root", assets.toString());
        values.put("assets_index_name", assetIndexId);
        values.put("auth_uuid", account.uuid());
        values.put("auth_access_token", account.minecraftAccessToken());
        values.put("auth_session", account.minecraftAccessToken());
        values.put("clientid", account.clientId());
        values.put("auth_xuid", account.xuid());
        values.put("user_type", "msa");
        values.put("user_properties", "{}");
        values.put("version_type", optionalStringUnchecked(profile, "type", "release"));
        values.put("natives_directory", natives.toString());
        values.put("launcher_name", "LiquidCopy");
        values.put("launcher_version", LauncherMetadata.bootstrapVersion());
        values.put("classpath", String.join(platformSeparator(), classpath.stream().map(Path::toString).toList()));
        values.put("classpath_separator", platformSeparator());
        values.put("library_directory", libraries.toString());
        values.put("resolution_width", options.width() == null ? "" : Integer.toString(options.width()));
        values.put("resolution_height", options.height() == null ? "" : Integer.toString(options.height()));
        values.put("primary_jar", root.resolve("versions").resolve(VERSION_ID).resolve(VERSION_JAR).toString());
        return values;
    }

    private List<String> resolveArguments(
        JsonArray entries,
        Map<String, String> substitutions,
        Map<String, Boolean> features
    ) throws IOException {
        List<String> resolved = new ArrayList<>();
        for (JsonElement entry : entries) {
            if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                resolved.add(substitute(entry.getAsString(), substitutions));
                continue;
            }
            if (!entry.isJsonObject()) {
                throw new IOException("Argument entry must be a string or object");
            }
            JsonObject conditional = entry.getAsJsonObject();
            if (!profileRulesAllow(conditional.get("rules"), features)) {
                continue;
            }
            JsonElement value = conditional.get("value");
            if (value == null) {
                throw new IOException("Conditional argument has no value");
            }
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                resolved.add(substitute(value.getAsString(), substitutions));
            } else if (value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) {
                    if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                        throw new IOException("Conditional argument array contains a non-string");
                    }
                    resolved.add(substitute(item.getAsString(), substitutions));
                }
            } else {
                throw new IOException("Conditional argument value must be a string or array");
            }
        }
        return resolved;
    }

    private static String substitute(String input, Map<String, String> substitutions) {
        Matcher matcher = PLACEHOLDER.matcher(input);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String replacement = substitutions.get(matcher.group(1));
            if (replacement == null) {
                replacement = matcher.group();
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static void ensureNoPlaceholders(List<String> command) throws IOException {
        for (String argument : command) {
            Matcher matcher = PLACEHOLDER.matcher(argument);
            if (matcher.find()) {
                throw new IOException("Version JSON contains unresolved placeholder ${" + matcher.group(1) + "}");
            }
        }
    }

    private static void requireLiquidCopyAgent(List<String> command, Path librariesDirectory) throws IOException {
        ProfileComposer composer = new ProfileComposer(LauncherMetadata.bootstrapVersion());
        Path expected = resolveInside(librariesDirectory, composer.libraryPath(), "LiquidCopy bootstrap");
        for (String argument : command) {
            if (!argument.startsWith("-javaagent:")) {
                continue;
            }
            try {
                Path actual = Path.of(argument.substring("-javaagent:".length())).toAbsolutePath().normalize();
                if (expected.equals(actual)) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // A malformed path is not the required LiquidCopy agent.
            }
        }
        throw new IOException("LiquidCopy bootstrap javaagent argument is missing");
    }

    private static Map<String, Boolean> featureFlags(LaunchOptions options) {
        Map<String, Boolean> features = new HashMap<>();
        features.put("is_demo_user", false);
        boolean resolution = options.width() != null;
        features.put("has_custom_resolution", resolution);
        features.put("has_quick_plays_support", false);
        features.put("is_quick_play_singleplayer", false);
        features.put("is_quick_play_multiplayer", false);
        features.put("is_quick_play_realms", false);
        return Collections.unmodifiableMap(features);
    }

    private boolean profileRulesAllow(JsonElement rulesElement, Map<String, Boolean> features) throws IOException {
        if (rulesElement == null || rulesElement.isJsonNull()) {
            return true;
        }
        if (!rulesElement.isJsonArray()) {
            throw new IOException("rules must be an array");
        }
        boolean allowed = false;
        for (JsonElement element : rulesElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IOException("rule must be an object");
            }
            JsonObject rule = element.getAsJsonObject();
            if (ruleMatches(rule, features, platform)) {
                String action = requiredString(rule, "action");
                if (!"allow".equals(action) && !"disallow".equals(action)) {
                    throw new IOException("Unknown rule action " + action);
                }
                allowed = "allow".equals(action);
            }
        }
        return allowed;
    }

    private static boolean ruleMatches(JsonObject rule, Map<String, Boolean> features, RuntimePlatform platform)
        throws IOException {
        JsonObject os = objectOrNull(rule, "os");
        if (os != null) {
            if (os.has("name") && !platformNameMatches(requiredString(os, "name"), platform.osName())) {
                return false;
            }
            if (os.has("arch") && !platform.matchesArch(requiredString(os, "arch"))) {
                return false;
            }
            if (os.has("version")) {
                String regex = requiredString(os, "version");
                try {
                    if (!Pattern.compile(regex).matcher(platform.osVersion()).find()) {
                        return false;
                    }
                } catch (RuntimeException exception) {
                    throw new IOException("Invalid OS version rule " + regex, exception);
                }
            }
        }
        JsonObject requiredFeatures = objectOrNull(rule, "features");
        if (requiredFeatures != null) {
            for (Map.Entry<String, JsonElement> feature : requiredFeatures.entrySet()) {
                if (!feature.getValue().isJsonPrimitive() || !feature.getValue().getAsJsonPrimitive().isBoolean()) {
                    throw new IOException("Feature rule " + feature.getKey() + " is not boolean");
                }
                if (features.getOrDefault(feature.getKey(), false) != feature.getValue().getAsBoolean()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean platformNameMatches(String ruleName, String actual) {
        return ruleName.equals(actual) || ("macos".equals(ruleName) && "osx".equals(actual));
    }

    private void validateNativePlatformSupport(JsonArray libraries, Map<String, Boolean> features)
        throws IOException {
        if (!"linux".equals(platform.osName()) || platform.architecture() == Architecture.X86_64) {
            return;
        }
        Set<String> nativeFamilies = new LinkedHashSet<>();
        Set<String> matchingFamilies = new LinkedHashSet<>();
        for (JsonElement element : libraries) {
            if (!element.isJsonObject()) {
                throw new IOException("libraries contains a non-object entry");
            }
            JsonObject library = element.getAsJsonObject();
            if (!profileRulesAllow(library.get("rules"), features)) {
                continue;
            }
            String name = optionalString(library, "name", "");
            String classifier = mavenClassifier(name);
            if (!isNativeClassifier(classifier) || !"linux".equals(classifierOs(normalizeClassifier(classifier)))) {
                continue;
            }
            String family = mavenFamily(name);
            nativeFamilies.add(family);
            if (artifactClassifierMatches(classifier, platform)) {
                matchingFamilies.add(family);
            }
        }
        Set<String> missing = new LinkedHashSet<>(nativeFamilies);
        missing.removeAll(matchingFamilies);
        if (nativeFamilies.isEmpty() || !missing.isEmpty()) {
            String details = nativeFamilies.isEmpty() ? "the profile declares no Linux native families"
                : "missing " + String.join(", ", missing.stream().limit(3).toList())
                    + (missing.size() > 3 ? " and " + (missing.size() - 3) + " more" : "");
            throw new IOException("Unsupported Linux " + platform.architecture().id()
                + " runtime: Minecraft 1.21.11 provides no complete matching native set (" + details + ")");
        }
    }

    private void validateProfile(JsonObject profile) throws IOException {
        if (!VERSION_ID.equals(requiredString(profile, "id"))) {
            throw new IOException("Expected version id " + VERSION_ID);
        }
        if (!MAIN_CLASS.equals(requiredString(profile, "mainClass"))) {
            throw new IOException("Unsupported Minecraft main class " + requiredString(profile, "mainClass"));
        }
        JsonObject javaVersion = requiredObject(profile, "javaVersion");
        if (!javaVersion.has("majorVersion") || javaVersion.get("majorVersion").getAsInt() != 21) {
            throw new IOException("Version JSON does not require Java 21");
        }
        requiredObject(profile, "downloads");
        if (expectedClientSha1 != null) {
            JsonObject client = requiredObject(requiredObject(profile, "downloads"), "client");
            String actualClientSha1 = requiredSha1(client, "sha1", "Minecraft client");
            if (!expectedClientSha1.equals(actualClientSha1)) {
                throw new IOException("Version JSON does not target Mojang's named 1.21.11 client");
            }
        }
        requiredObject(profile, "assetIndex");
        requiredArray(profile, "libraries");
        JsonObject args = requiredObject(profile, "arguments");
        requiredArray(args, "jvm");
        requiredArray(args, "game");
    }

    private Artifact artifact(JsonObject parent, String field, String label) throws IOException {
        if (parent == null) {
            throw new IOException("Missing descriptor for " + label);
        }
        JsonObject object = objectOrNull(parent, field);
        if (object == null) {
            throw new IOException("Missing descriptor for " + label);
        }
        return artifact(object, label);
    }

    private Artifact artifact(JsonObject object, String label) throws IOException {
        String path = object.has("path") ? requiredString(object, "path") : label.replace(' ', '_');
        if (path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*")) {
            throw new IOException("Unsafe artifact path " + path);
        }
        for (String part : path.replace('\\', '/').split("/")) {
            if ("..".equals(part)) {
                throw new IOException("Unsafe artifact path " + path);
            }
        }
        String sha1 = requiredSha1(object, "sha1", label);
        long size = requiredNonNegativeLong(object, "size", label);
        URI uri = null;
        if (object.has("url") && object.get("url").isJsonPrimitive()) {
            try {
                uri = URI.create(object.get("url").getAsString());
            } catch (RuntimeException exception) {
                throw new IOException("Invalid URL for " + label, exception);
            }
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Artifact URL must use HTTPS for " + label);
            }
        }
        return new Artifact(path, sha1, size, uri, label);
    }

    private static String requiredSha1(JsonObject object, String field, String label) throws IOException {
        String hash = requiredString(object, field).toLowerCase(Locale.ROOT);
        if (!hash.matches("[0-9a-f]{40}")) {
            throw new IOException("Invalid SHA-1 for " + label);
        }
        return hash;
    }

    private static long requiredNonNegativeLong(JsonObject object, String field, String label) throws IOException {
        try {
            long value = object.get(field).getAsLong();
            if (value < 0) {
                throw new IOException("Negative size for " + label);
            }
            return value;
        } catch (NullPointerException | UnsupportedOperationException | NumberFormatException exception) {
            throw new IOException("Invalid size for " + label, exception);
        }
    }

    private static List<String> extractionExcludes(JsonObject library) throws IOException {
        JsonObject extract = objectOrNull(library, "extract");
        if (extract == null || !extract.has("exclude")) {
            return List.of("META-INF/");
        }
        JsonArray array = extract.getAsJsonArray("exclude");
        List<String> excludes = new ArrayList<>();
        for (JsonElement value : array) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IOException("Native extraction exclusion is not a string");
            }
            excludes.add(value.getAsString());
        }
        return List.copyOf(excludes);
    }

    private static String mavenClassifier(String name) {
        String[] segments = name.split(":", -1);
        return segments.length >= 4 ? segments[3].toLowerCase(Locale.ROOT) : "";
    }

    private static String mavenFamily(String name) {
        String[] segments = name.split(":", -1);
        return segments.length >= 3 ? String.join(":", segments[0], segments[1], segments[2]) : name;
    }

    private static boolean isNativeClassifier(String classifier) {
        return normalizeClassifier(classifier).startsWith("natives-");
    }

    /** Applies architecture selection to both LWJGL natives-* and Netty OS-arch classifiers. */
    static boolean artifactClassifierMatches(String classifier, RuntimePlatform platform) {
        String value = normalizeClassifier(classifier);
        if (value.isEmpty()) {
            return true;
        }
        String classifierOs = classifierOs(value);
        Architecture classifierArchitecture = classifierArchitecture(value);
        if (classifierOs == null || classifierArchitecture == null) {
            return true;
        }
        return platformNameMatches(classifierOs, platform.osName())
            && classifierArchitecture == platform.architecture();
    }

    static boolean nativeClassifierMatches(String classifier, RuntimePlatform platform) {
        String value = normalizeClassifier(classifier);
        return isNativeClassifier(value) && classifierOs(value) != null
            && artifactClassifierMatches(value, platform);
    }

    private static String normalizeClassifier(String classifier) {
        return classifier.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String classifierOs(String value) {
        String withoutNativePrefix = value.startsWith("natives-") ? value.substring("natives-".length()) : value;
        if (withoutNativePrefix.equals("windows") || withoutNativePrefix.startsWith("windows-")) {
            return "windows";
        }
        if (withoutNativePrefix.equals("linux") || withoutNativePrefix.startsWith("linux-")) {
            return "linux";
        }
        if (withoutNativePrefix.equals("osx") || withoutNativePrefix.startsWith("osx-")
            || withoutNativePrefix.equals("macos") || withoutNativePrefix.startsWith("macos-")) {
            return "osx";
        }
        return null;
    }

    private static Architecture classifierArchitecture(String value) {
        if (value.endsWith("-patch")) {
            // Mojang's 1.21.11 lwjgl-freetype natives-macos-patch is the Intel/x64 supplement.
            return Architecture.X86_64;
        }
        if (value.endsWith("-aarch-64") || value.endsWith("-aarch64") || value.endsWith("-arm64")) {
            return Architecture.ARM64;
        }
        if (value.endsWith("-x86-64") || value.endsWith("-amd64") || value.endsWith("-x64")) {
            return Architecture.X86_64;
        }
        if (value.endsWith("-x86") || value.endsWith("-i386") || value.endsWith("-i686")) {
            return Architecture.X86;
        }
        if (value.endsWith("-arm32") || value.endsWith("-arm")) {
            return Architecture.ARM32;
        }
        if (value.endsWith("-ppc64le")) {
            return Architecture.PPC64LE;
        }
        if (value.endsWith("-riscv64")) {
            return Architecture.RISCV64;
        }
        // The unsuffixed natives-windows/linux/macos artifacts are x86_64 in this Mojang profile.
        return isNativeClassifier(value) && classifierOs(value) != null ? Architecture.X86_64 : null;
    }

    private static JsonObject readObject(Path path, String label, int maxBytes) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException(label + " is missing at " + path);
        }
        if (Files.size(path) > maxBytes) {
            throw new IOException(label + " is unexpectedly large");
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException(label + " is not an object");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Unable to parse " + label, exception);
        }
    }

    private static JsonObject requiredObject(JsonObject parent, String field) throws IOException {
        JsonObject value = objectOrNull(parent, field);
        if (value == null) {
            throw new IOException("Missing object field " + field);
        }
        return value;
    }

    private static JsonObject objectOrNull(JsonObject parent, String field) throws IOException {
        JsonElement value = parent.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonObject()) {
            throw new IOException("Field " + field + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject parent, String field) throws IOException {
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IOException("Missing array field " + field);
        }
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonObject parent, String field) throws IOException {
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Missing string field " + field);
        }
        return value.getAsString();
    }

    private static String requiredStringUnchecked(JsonObject parent, String field) {
        try {
            return requiredString(parent, field);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String optionalString(JsonObject parent, String field, String fallback) throws IOException {
        JsonElement value = parent.get(field);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Field " + field + " must be a string");
        }
        return value.getAsString();
    }

    private static String optionalStringUnchecked(JsonObject parent, String field, String fallback) {
        try {
            return optionalString(parent, field, fallback);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path resolveInside(Path root, String relative, String label) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IOException(label + " leaves data directory: " + relative);
        }
        return target;
    }

    private static Path normalizeRoot(Path root) {
        return Objects.requireNonNull(root, "dataDirectory").toAbsolutePath().normalize();
    }

    private static String sha1(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            moveReplace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String platformSeparator() {
        return java.io.File.pathSeparator;
    }

    private static ArtifactDownloader httpDownloader() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        return (uri, target) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", "LiquidCopy-Launcher/1.21.11")
                .GET().build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() != 200) {
                throw new IOException("Download failed with HTTP " + response.statusCode() + " for " + uri);
            }
        };
    }

    private static Process startProcess(List<String> command, Path workingDirectory, Path logFile) throws IOException {
        Files.createDirectories(logFile.toAbsolutePath().normalize().getParent());
        return new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
            .start();
    }

    private static JavaRuntime inspectJava(Path executable) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(executable.toString(), "-version")
            .redirectErrorStream(true).start();
        String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("Java check failed with exit " + exit + ": " + output.strip());
        }
        Matcher matcher = JAVA_VERSION.matcher(output);
        if (!matcher.find()) {
            throw new IOException("Unable to identify Java version from " + executable + ": " + output.strip());
        }
        return new JavaRuntime(Integer.parseInt(matcher.group(1)), output.strip());
    }

    public record AuthenticatedAccount(
        String playerName,
        String uuid,
        String minecraftAccessToken,
        String xuid,
        String clientId
    ) {
        public AuthenticatedAccount {
            playerName = requireNonBlank(playerName, "playerName");
            uuid = requireNonBlank(uuid, "uuid").replace("-", "").toLowerCase(Locale.ROOT);
            if (!uuid.matches("[0-9a-f]{32}")) {
                throw new IllegalArgumentException("uuid must contain 32 hexadecimal characters");
            }
            minecraftAccessToken = requireNonBlank(minecraftAccessToken, "minecraftAccessToken");
            xuid = requireOptionalControlFree(xuid, "xuid");
            clientId = requireNonBlank(clientId, "clientId");
        }

        @Override
        public String toString() {
            return "AuthenticatedAccount[playerName=" + playerName + ", uuid=" + uuid + ", xuid=" + xuid
                + ", clientId=" + clientId + ", minecraftAccessToken=<redacted>]";
        }
    }

    public record LaunchOptions(
        Path javaExecutable,
        int minMemoryMiB,
        int maxMemoryMiB,
        Integer width,
        Integer height,
        List<String> extraJvmArguments
    ) {
        public LaunchOptions {
            javaExecutable = javaExecutable == null ? defaultJavaExecutable() : javaExecutable.toAbsolutePath().normalize();
            if (minMemoryMiB < 256 || maxMemoryMiB < minMemoryMiB) {
                throw new IllegalArgumentException("Memory must be at least 256 MiB and max must be >= min");
            }
            if ((width == null) != (height == null)) {
                throw new IllegalArgumentException("width and height must either both be set or both be null");
            }
            if (width != null && (width < 320 || height < 240)) {
                throw new IllegalArgumentException("Custom resolution is too small");
            }
            extraJvmArguments = extraJvmArguments == null ? List.of() : List.copyOf(extraJvmArguments);
            for (String argument : extraJvmArguments) {
                if (argument == null || argument.isBlank()) {
                    throw new IllegalArgumentException("extraJvmArguments contains a blank value");
                }
            }
        }

        public static LaunchOptions defaults() {
            return new LaunchOptions(null, 512, 4096, null, null, List.of());
        }

        public static LaunchOptions withMaxMemoryMiB(int maxMemoryMiB) {
            return new LaunchOptions(null, Math.min(512, maxMemoryMiB), maxMemoryMiB, null, null, List.of());
        }

        private static Path defaultJavaExecutable() {
            // java.exe is intentionally used on Windows: unlike javaw.exe it exposes
            // `-version` output and its game output can be redirected into latest.log.
            String binary = RuntimePlatform.current().osName().equals("windows") ? "java.exe" : "java";
            Path candidate = Path.of(System.getProperty("java.home"), "bin", binary);
            return candidate.toAbsolutePath().normalize();
        }
    }

    public record PreparedLaunch(
        List<String> command,
        Path workingDirectory,
        Path nativesDirectory,
        Path logFile,
        int downloadedFiles,
        long downloadedBytes
    ) {
        public PreparedLaunch {
            command = List.copyOf(command);
            workingDirectory = workingDirectory.toAbsolutePath().normalize();
            nativesDirectory = nativesDirectory.toAbsolutePath().normalize();
            logFile = logFile.toAbsolutePath().normalize();
            if (command.isEmpty()) {
                throw new IllegalArgumentException("command is empty");
            }
        }

        @Override
        public String toString() {
            return "PreparedLaunch[command=<" + command.size() + " arguments; credentials redacted>, "
                + "workingDirectory=" + workingDirectory + ", nativesDirectory=" + nativesDirectory
                + ", logFile=" + logFile + ", downloadedFiles=" + downloadedFiles
                + ", downloadedBytes=" + downloadedBytes + ']';
        }
    }

    public record LaunchResult(Process process, PreparedLaunch preparation) {
        public LaunchResult {
            Objects.requireNonNull(process, "process");
            Objects.requireNonNull(preparation, "preparation");
        }
    }

    public record Progress(String phase, String item, int completed, int total) {
        public Progress {
            phase = requireNonBlank(phase, "phase");
            item = item == null ? "" : item;
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = progress -> { };

        void update(Progress progress);
    }

    @FunctionalInterface
    interface ArtifactDownloader {
        void download(URI uri, Path target) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command, Path workingDirectory, Path logFile) throws IOException;
    }

    @FunctionalInterface
    interface JavaInspector {
        JavaRuntime inspect(Path executable) throws IOException, InterruptedException;
    }

    record JavaRuntime(int majorVersion, String description) {
    }

    record RuntimePlatform(String osName, Architecture architecture, String osVersion) {
        RuntimePlatform {
            Objects.requireNonNull(osName, "osName");
            Objects.requireNonNull(architecture, "architecture");
            Objects.requireNonNull(osVersion, "osVersion");
        }

        static RuntimePlatform current() {
            String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
            String name = os.startsWith("windows") ? "windows" : os.contains("mac") || os.contains("darwin")
                ? "osx" : os.contains("linux") ? "linux" : "unknown";
            return new RuntimePlatform(name, Architecture.from(System.getProperty("os.arch", "unknown")),
                System.getProperty("os.version", ""));
        }

        String archBits() {
            return architecture == Architecture.X86 ? "32" : "64";
        }

        boolean matchesArch(String rule) {
            String normalized = rule.toLowerCase(Locale.ROOT).replace('_', '-');
            return switch (architecture) {
                case X86_64 -> Set.of("x86-64", "x86_64", "amd64", "x64").contains(normalized);
                case X86 -> Set.of("x86", "i386", "i486", "i586", "i686").contains(normalized);
                case ARM64 -> Set.of("arm64", "aarch64").contains(normalized);
                case ARM32 -> Set.of("arm", "arm32").contains(normalized);
                case PPC64LE -> "ppc64le".equals(normalized);
                case RISCV64 -> "riscv64".equals(normalized);
                case OTHER -> false;
            };
        }
    }

    enum Architecture {
        X86_64("x86_64"), X86("x86"), ARM64("arm64"), ARM32("arm32"), PPC64LE("ppc64le"),
        RISCV64("riscv64"), OTHER("other");

        private final String id;

        Architecture(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        static Architecture from(String value) {
            String arch = value.toLowerCase(Locale.ROOT).replace('_', '-');
            if (Set.of("amd64", "x86-64", "x64").contains(arch)) return X86_64;
            if (Set.of("x86", "i386", "i486", "i586", "i686").contains(arch)) return X86;
            if (Set.of("aarch64", "arm64").contains(arch)) return ARM64;
            if (arch.startsWith("arm")) return ARM32;
            if (arch.equals("ppc64le")) return PPC64LE;
            if (arch.equals("riscv64")) return RISCV64;
            return OTHER;
        }
    }

    private record Artifact(String path, String sha1, long size, URI uri, String label) {
        private Artifact(String path, String sha1, long size, URI uri) {
            this(path, sha1, size, uri, path);
        }
    }

    private record ResolvedArtifact(Artifact artifact, Path target, String label) {
    }

    private record NativeLibrary(Path jar, List<String> excludes) {
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.indexOf('\0') >= 0
            || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " is blank or contains a control character");
        }
        return normalized;
    }

    private static String requireOptionalControlFree(String value, String label) {
        Objects.requireNonNull(value, label);
        String normalized = value.trim();
        if (normalized.indexOf('\0') >= 0 || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " contains a control character");
        }
        return normalized;
    }
}
