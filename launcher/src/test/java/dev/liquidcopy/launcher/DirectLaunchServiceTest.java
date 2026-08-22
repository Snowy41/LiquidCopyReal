package dev.liquidcopy.launcher;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectLaunchServiceTest {
    private static final DirectLaunchService.RuntimePlatform WINDOWS_X64 =
        new DirectLaunchService.RuntimePlatform("windows", DirectLaunchService.Architecture.X86_64, "11.0");

    @TempDir
    Path temporary;

    @Test
    void preparesCompleteDirectCommandAndRuntimeFiles() throws Exception {
        Fixture fixture = new Fixture(temporary, nativeJar("windows/x64/org/lwjgl/glfw/glfw.dll", "native-x64"));
        fixture.writeProfile(false, false);
        CapturingStarter starter = new CapturingStarter();
        DirectLaunchService service = service(fixture, starter, 21);
        List<DirectLaunchService.Progress> progress = java.util.Collections.synchronizedList(new ArrayList<>());

        DirectLaunchService.PreparedLaunch prepared = service.prepare(
            temporary,
            account(),
            new DirectLaunchService.LaunchOptions(Path.of("fake-java"), 512, 3072, 1280, 720,
                List.of("-Dcustom=true")),
            progress::add
        );

        assertEquals(7, prepared.downloadedFiles());
        assertTrue(prepared.downloadedBytes() > 0);
        assertEquals(InstallService.instanceDirectory(temporary), prepared.workingDirectory());
        assertEquals(temporary.resolve("logs/latest.log").toAbsolutePath().normalize(), prepared.logFile());
        assertTrue(Files.isRegularFile(temporary.resolve("versions/LiquidCopy-1.21.11/LiquidCopy-1.21.11.jar")));
        assertTrue(Files.isRegularFile(temporary.resolve("libraries").resolve(Fixture.AGENT_PATH)));
        assertTrue(Files.isRegularFile(temporary.resolve("assets/objects").resolve(fixture.assetHash.substring(0, 2))
            .resolve(fixture.assetHash)));
        assertEquals("native-x64", Files.readString(prepared.nativesDirectory().resolve("glfw.dll")));
        assertFalse(fixture.downloaded.contains(Fixture.NATIVE_ARM_URI));
        assertFalse(fixture.downloaded.contains(Fixture.LINUX_LIBRARY_URI));

        List<String> command = prepared.command();
        assertTrue(Path.of(command.get(0)).endsWith("fake-java"));
        assertTrue(command.contains("-Xms512M"));
        assertTrue(command.contains("-Xmx3072M"));
        assertTrue(command.contains("-Dcustom=true"));
        assertTrue(command.contains("-Dwindows=true"));
        assertFalse(command.contains("-Dlinux=true"));
        String agentArgument = command.stream().filter(value -> value.startsWith("-javaagent:"))
            .findFirst().orElseThrow();
        assertEquals(temporary.resolve("libraries").resolve(Fixture.AGENT_PATH).toAbsolutePath().normalize(),
            Path.of(agentArgument.substring("-javaagent:".length())).toAbsolutePath().normalize());
        assertTrue(command.contains("net.minecraft.client.main.Main"));
        assertArgument(command, "--username", "TestPlayer");
        assertArgument(command, "--uuid", "0123456789abcdef0123456789abcdef");
        assertArgument(command, "--accessToken", "minecraft-token");
        assertArgument(command, "--xuid", "123456789");
        assertArgument(command, "--clientId", "oauth-client");
        assertArgument(command, "--width", "1280");
        assertArgument(command, "--height", "720");
        assertTrue(command.stream().noneMatch(value -> value.contains("${")));
        String classpath = command.get(command.indexOf("-cp") + 1);
        assertTrue(classpath.contains(Path.of(Fixture.AGENT_PATH).getFileName().toString()));
        assertTrue(classpath.contains("windows-1.jar"));
        assertTrue(classpath.contains("natives-windows.jar"));
        assertTrue(classpath.contains("LiquidCopy-1.21.11.jar"));
        assertFalse(classpath.contains("linux-1.jar"));
        assertFalse(classpath.contains("natives-windows-arm64.jar"));
        assertTrue(progress.stream().anyMatch(item -> item.phase().equals("assets")));

        Process process = service.start(prepared);
        assertEquals(starter.process, process);
        assertEquals(prepared.command(), starter.command);
        assertEquals(prepared.workingDirectory(), starter.workingDirectory);
        assertEquals(prepared.logFile(), starter.logFile);
    }

    @Test
    void cacheIsHashVerifiedAndAvoidsSecondDownload() throws Exception {
        Fixture fixture = new Fixture(temporary, nativeJar("windows/x64/lib.dll", "native"));
        fixture.writeProfile(false, false);
        DirectLaunchService service = service(fixture, new CapturingStarter(), 21);

        DirectLaunchService.PreparedLaunch first = service.prepare(temporary, account(),
            DirectLaunchService.LaunchOptions.defaults());
        fixture.downloaded.clear();
        DirectLaunchService.PreparedLaunch second = service.prepare(temporary, account(),
            DirectLaunchService.LaunchOptions.defaults());

        assertTrue(first.downloadedFiles() > 0);
        assertEquals(0, second.downloadedFiles());
        assertTrue(fixture.downloaded.isEmpty());
    }

    @Test
    void emptyXuidIsNormalizedAndPassedAsAnEmptyGameArgument() throws Exception {
        Fixture fixture = new Fixture(temporary, nativeJar("windows/x64/lib.dll", "native"));
        fixture.writeProfile(false, false);
        DirectLaunchService service = service(fixture, new CapturingStarter(), 21);
        DirectLaunchService.AuthenticatedAccount withoutXuid = new DirectLaunchService.AuthenticatedAccount(
            "TestPlayer", "0123456789abcdef0123456789abcdef", "minecraft-token", "   ", "oauth-client");

        DirectLaunchService.PreparedLaunch prepared = service.prepare(temporary, withoutXuid,
            DirectLaunchService.LaunchOptions.defaults());

        assertEquals("", withoutXuid.xuid());
        assertArgument(prepared.command(), "--xuid", "");
        assertThrows(IllegalArgumentException.class, () -> new DirectLaunchService.AuthenticatedAccount(
            "TestPlayer", "0123456789abcdef0123456789abcdef", "minecraft-token", "bad\r\nxuid",
            "oauth-client"));
    }

    @Test
    void rejectsDownloadedHashMismatch() throws Exception {
        Fixture fixture = new Fixture(temporary, nativeJar("windows/x64/lib.dll", "native"));
        fixture.writeProfile(true, false);
        DirectLaunchService service = service(fixture, new CapturingStarter(), 21);

        IOException error = assertThrows(IOException.class, () -> service.prepare(temporary, account(),
            DirectLaunchService.LaunchOptions.defaults()));

        assertTrue(error.getMessage().contains("SHA-1 mismatch"));
        assertFalse(Files.exists(temporary.resolve("versions/LiquidCopy-1.21.11/LiquidCopy-1.21.11.jar")));
    }

    @Test
    void rejectsNativeZipTraversalBeforeWritingOutsideRuntime() throws Exception {
        Fixture fixture = new Fixture(temporary, nativeJar("../escape.dll", "escaped"));
        fixture.writeProfile(false, false);
        DirectLaunchService service = service(fixture, new CapturingStarter(), 21);

        IOException error = assertThrows(IOException.class, () -> service.prepare(temporary, account(),
            DirectLaunchService.LaunchOptions.defaults()));

        assertTrue(error.getMessage().contains("Unsafe native ZIP entry"));
        assertFalse(Files.exists(temporary.resolve("escape.dll")));
    }

    @Test
    void requiresExactlyJava21BeforeResolvingRuntime() throws Exception {
        Fixture fixture = new Fixture(temporary, new byte[0]);
        DirectLaunchService service = service(fixture, new CapturingStarter(), 17);

        IOException error = assertThrows(IOException.class, () -> service.prepare(temporary, account(),
            DirectLaunchService.LaunchOptions.defaults()));

        assertTrue(error.getMessage().contains("requires Java 21"));
        assertTrue(fixture.downloaded.isEmpty());
    }

    @Test
    void nativeClassifierSelectionIsArchitectureSpecific() {
        assertTrue(DirectLaunchService.nativeClassifierMatches("natives-windows", WINDOWS_X64));
        assertFalse(DirectLaunchService.nativeClassifierMatches("natives-windows-arm64", WINDOWS_X64));
        assertFalse(DirectLaunchService.nativeClassifierMatches("natives-windows-x86", WINDOWS_X64));
        assertFalse(DirectLaunchService.nativeClassifierMatches("natives-linux", WINDOWS_X64));
        DirectLaunchService.RuntimePlatform arm64 = new DirectLaunchService.RuntimePlatform("windows",
            DirectLaunchService.Architecture.ARM64, "11.0");
        assertTrue(DirectLaunchService.nativeClassifierMatches("natives-windows-arm64", arm64));
        assertFalse(DirectLaunchService.nativeClassifierMatches("natives-windows", arm64));

        DirectLaunchService.RuntimePlatform macArm64 = new DirectLaunchService.RuntimePlatform("osx",
            DirectLaunchService.Architecture.ARM64, "15.0");
        DirectLaunchService.RuntimePlatform macX64 = new DirectLaunchService.RuntimePlatform("osx",
            DirectLaunchService.Architecture.X86_64, "15.0");
        assertTrue(DirectLaunchService.nativeClassifierMatches("natives-macos-arm64", macArm64));
        assertFalse(DirectLaunchService.nativeClassifierMatches("natives-macos", macArm64));
        assertFalse(DirectLaunchService.nativeClassifierMatches("natives-macos-patch", macArm64));
        assertTrue(DirectLaunchService.nativeClassifierMatches("natives-macos-patch", macX64));

        DirectLaunchService.RuntimePlatform linuxArm64 = new DirectLaunchService.RuntimePlatform("linux",
            DirectLaunchService.Architecture.ARM64, "6.12");
        DirectLaunchService.RuntimePlatform linuxX64 = new DirectLaunchService.RuntimePlatform("linux",
            DirectLaunchService.Architecture.X86_64, "6.12");
        assertTrue(DirectLaunchService.artifactClassifierMatches("linux-aarch_64", linuxArm64));
        assertFalse(DirectLaunchService.artifactClassifierMatches("linux-x86_64", linuxArm64));
        assertTrue(DirectLaunchService.artifactClassifierMatches("linux-x86_64", linuxX64));
        assertFalse(DirectLaunchService.artifactClassifierMatches("linux-aarch_64", linuxX64));
        assertTrue(DirectLaunchService.artifactClassifierMatches("osx-aarch_64", macArm64));
        assertFalse(DirectLaunchService.artifactClassifierMatches("osx-x86_64", macArm64));
    }

    @Test
    void macArmPreparationSelectsArmNativeAndNettyButNotIntelPatch() throws Exception {
        Fixture fixture = new Fixture(temporary, nativeJar("windows/x64/lib.dll", "native-windows"));
        fixture.writeProfile(false, false);
        DirectLaunchService.RuntimePlatform macArm64 = new DirectLaunchService.RuntimePlatform("osx",
            DirectLaunchService.Architecture.ARM64, "15.0");
        DirectLaunchService service = service(fixture, new CapturingStarter(), 21, macArm64);

        DirectLaunchService.PreparedLaunch prepared = service.prepare(temporary, account(),
            DirectLaunchService.LaunchOptions.defaults());

        assertTrue(fixture.downloaded.contains(Fixture.MAC_ARM_NATIVE_URI));
        assertFalse(fixture.downloaded.contains(Fixture.MAC_PATCH_NATIVE_URI));
        assertTrue(fixture.downloaded.contains(Fixture.NETTY_MAC_ARM_URI));
        assertFalse(fixture.downloaded.contains(Fixture.NETTY_MAC_X64_URI));
        assertEquals("native-macos-arm64", Files.readString(prepared.nativesDirectory().resolve("glfw.dylib")));
        String classpath = prepared.command().get(prepared.command().indexOf("-cp") + 1);
        assertTrue(classpath.contains("lwjgl-1-natives-macos-arm64.jar"));
        assertFalse(classpath.contains("lwjgl-freetype-1-natives-macos-patch.jar"));
        assertTrue(classpath.contains("netty-transport-native-kqueue-1-osx-aarch_64.jar"));
        assertFalse(classpath.contains("netty-transport-native-kqueue-1-osx-x86_64.jar"));
    }

    @Test
    void rejectsUnsupportedLinuxArmBeforeDownloadingArtifacts() throws Exception {
        Fixture fixture = new Fixture(temporary, nativeJar("windows/x64/lib.dll", "native-windows"));
        fixture.writeProfile(false, false);
        DirectLaunchService.RuntimePlatform linuxArm64 = new DirectLaunchService.RuntimePlatform("linux",
            DirectLaunchService.Architecture.ARM64, "6.12");
        DirectLaunchService service = service(fixture, new CapturingStarter(), 21, linuxArm64);

        IOException error = assertThrows(IOException.class, () -> service.prepare(temporary, account(),
            DirectLaunchService.LaunchOptions.defaults()));

        assertTrue(error.getMessage().contains("Unsupported Linux arm64 runtime"));
        assertTrue(error.getMessage().contains("no complete matching native set"));
        assertTrue(fixture.downloaded.isEmpty());
    }

    @Test
    void rejectsEveryUnsupportedNonX64LinuxArchitectureEarly() throws Exception {
        Fixture fixture = new Fixture(temporary, nativeJar("windows/x64/lib.dll", "native-windows"));
        fixture.writeProfile(false, false);
        for (DirectLaunchService.Architecture architecture : List.of(
            DirectLaunchService.Architecture.X86,
            DirectLaunchService.Architecture.ARM32,
            DirectLaunchService.Architecture.PPC64LE,
            DirectLaunchService.Architecture.RISCV64,
            DirectLaunchService.Architecture.OTHER
        )) {
            DirectLaunchService.RuntimePlatform linux = new DirectLaunchService.RuntimePlatform(
                "linux", architecture, "6.12");
            DirectLaunchService service = service(fixture, new CapturingStarter(), 21, linux);
            IOException error = assertThrows(IOException.class, () -> service.prepare(temporary, account(),
                DirectLaunchService.LaunchOptions.defaults()));
            assertTrue(error.getMessage().contains("Unsupported Linux " + architecture.id() + " runtime"));
        }
        assertTrue(fixture.downloaded.isEmpty());
    }

    private static DirectLaunchService service(Fixture fixture, CapturingStarter starter, int javaVersion) {
        return service(fixture, starter, javaVersion, WINDOWS_X64);
    }

    private static DirectLaunchService service(Fixture fixture, CapturingStarter starter, int javaVersion,
        DirectLaunchService.RuntimePlatform platform) {
        return new DirectLaunchService(fixture::download, platform, starter,
            executable -> new DirectLaunchService.JavaRuntime(javaVersion, "test"));
    }

    private static DirectLaunchService.AuthenticatedAccount account() {
        return new DirectLaunchService.AuthenticatedAccount("TestPlayer",
            "01234567-89ab-cdef-0123-456789abcdef", "minecraft-token", "123456789", "oauth-client");
    }

    private static void assertArgument(List<String> command, String name, String value) {
        int index = command.indexOf(name);
        assertTrue(index >= 0, () -> "Missing " + name + " in " + command);
        assertEquals(value, command.get(index + 1));
    }

    private static byte[] nativeJar(String entryName, String contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(contents.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static final class Fixture {
        static final URI CLIENT_URI = URI.create("https://example.invalid/client.jar");
        static final URI AGENT_URI = URI.create("https://example.invalid/agent.jar");
        static final URI WINDOWS_LIBRARY_URI = URI.create("https://example.invalid/windows.jar");
        static final URI LINUX_LIBRARY_URI = URI.create("https://example.invalid/linux.jar");
        static final URI NATIVE_X64_URI = URI.create("https://example.invalid/native-x64.jar");
        static final URI NATIVE_ARM_URI = URI.create("https://example.invalid/native-arm.jar");
        static final URI LINUX_NATIVE_URI = URI.create("https://example.invalid/native-linux.jar");
        static final URI MAC_ARM_NATIVE_URI = URI.create("https://example.invalid/native-macos-arm64.jar");
        static final URI MAC_PATCH_NATIVE_URI = URI.create("https://example.invalid/native-macos-patch.jar");
        static final URI NETTY_MAC_ARM_URI = URI.create("https://example.invalid/netty-osx-aarch64.jar");
        static final URI NETTY_MAC_X64_URI = URI.create("https://example.invalid/netty-osx-x64.jar");
        static final URI ASSET_INDEX_URI = URI.create("https://example.invalid/assets.json");
        static final URI LOGGING_URI = URI.create("https://example.invalid/logging.xml");
        static final ProfileComposer COMPOSER = new ProfileComposer(LauncherMetadata.bootstrapVersion());
        static final String AGENT_PATH = COMPOSER.libraryPath();

        final Path root;
        final Map<URI, byte[]> payloads = new ConcurrentHashMap<>();
        final List<URI> downloaded = java.util.Collections.synchronizedList(new ArrayList<>());
        final byte[] client = "named-client".getBytes(StandardCharsets.UTF_8);
        final byte[] agent = "liquidcopy-agent".getBytes(StandardCharsets.UTF_8);
        final byte[] windowsLibrary = "windows-library".getBytes(StandardCharsets.UTF_8);
        final byte[] linuxLibrary = "linux-library".getBytes(StandardCharsets.UTF_8);
        final byte[] nativeX64;
        final byte[] nativeArm;
        final byte[] nativeLinux;
        final byte[] nativeMacArm;
        final byte[] nativeMacPatch;
        final byte[] nettyMacArm = "netty-macos-arm64".getBytes(StandardCharsets.UTF_8);
        final byte[] nettyMacX64 = "netty-macos-x64".getBytes(StandardCharsets.UTF_8);
        final byte[] asset = "asset-object".getBytes(StandardCharsets.UTF_8);
        final String assetHash = Hashing.sha1(asset);
        final byte[] logging = "<Configuration/>".getBytes(StandardCharsets.UTF_8);
        final byte[] assetIndex;

        Fixture(Path root, byte[] nativeX64) throws IOException {
            this.root = root;
            this.nativeX64 = nativeX64;
            this.nativeArm = "wrong-architecture".getBytes(StandardCharsets.UTF_8);
            this.nativeLinux = nativeJar("linux/x64/org/lwjgl/liblwjgl.so", "native-linux-x64");
            this.nativeMacArm = nativeJar("osx/arm64/org/lwjgl/glfw.dylib", "native-macos-arm64");
            this.nativeMacPatch = nativeJar("osx/x64/org/lwjgl/glfw.dylib", "native-macos-patch-x64");
            JsonObject object = new JsonObject();
            object.addProperty("hash", assetHash);
            object.addProperty("size", asset.length);
            JsonObject objects = new JsonObject();
            objects.add("minecraft/test.txt", object);
            JsonObject index = new JsonObject();
            index.add("objects", objects);
            assetIndex = new Gson().toJson(index).getBytes(StandardCharsets.UTF_8);

            payloads.put(CLIENT_URI, client);
            payloads.put(AGENT_URI, agent);
            payloads.put(WINDOWS_LIBRARY_URI, windowsLibrary);
            payloads.put(LINUX_LIBRARY_URI, linuxLibrary);
            payloads.put(NATIVE_X64_URI, nativeX64);
            payloads.put(NATIVE_ARM_URI, nativeArm);
            payloads.put(LINUX_NATIVE_URI, nativeLinux);
            payloads.put(MAC_ARM_NATIVE_URI, nativeMacArm);
            payloads.put(MAC_PATCH_NATIVE_URI, nativeMacPatch);
            payloads.put(NETTY_MAC_ARM_URI, nettyMacArm);
            payloads.put(NETTY_MAC_X64_URI, nettyMacX64);
            payloads.put(ASSET_INDEX_URI, assetIndex);
            payloads.put(URI.create("https://resources.download.minecraft.net/" + assetHash.substring(0, 2)
                + '/' + assetHash), asset);
            payloads.put(LOGGING_URI, logging);
        }

        void writeProfile(boolean wrongClientHash, boolean unresolvedArgument) throws IOException {
            JsonObject profile = new JsonObject();
            profile.addProperty("id", ProfileComposer.CUSTOM_VERSION_ID);
            profile.addProperty("type", "release");
            profile.addProperty("mainClass", "net.minecraft.client.main.Main");
            JsonObject java = new JsonObject();
            java.addProperty("majorVersion", 21);
            profile.add("javaVersion", java);

            JsonObject clientDescriptor = descriptor(null, client, CLIENT_URI);
            if (wrongClientHash) {
                clientDescriptor.addProperty("sha1", "0000000000000000000000000000000000000000");
            }
            JsonObject downloads = new JsonObject();
            downloads.add("client", clientDescriptor);
            profile.add("downloads", downloads);

            JsonObject assetDescriptor = descriptor(null, assetIndex, ASSET_INDEX_URI);
            assetDescriptor.addProperty("id", "test-assets");
            profile.add("assetIndex", assetDescriptor);

            JsonArray libraries = new JsonArray();
            libraries.add(library(COMPOSER.libraryCoordinates(), AGENT_PATH, agent,
                AGENT_URI, null));
            libraries.add(library("test:windows:1", "test/windows/1/windows-1.jar", windowsLibrary,
                WINDOWS_LIBRARY_URI, osRules("windows")));
            libraries.add(library("test:linux:1", "test/linux/1/linux-1.jar", linuxLibrary,
                LINUX_LIBRARY_URI, osRules("linux")));
            libraries.add(library("org.lwjgl:lwjgl:1:natives-windows",
                "org/lwjgl/lwjgl/1/lwjgl-1-natives-windows.jar", nativeX64, NATIVE_X64_URI,
                osRules("windows")));
            libraries.add(library("org.lwjgl:lwjgl:1:natives-windows-arm64",
                "org/lwjgl/lwjgl/1/lwjgl-1-natives-windows-arm64.jar", nativeArm, NATIVE_ARM_URI,
                osRules("windows")));
            libraries.add(library("org.lwjgl:lwjgl:1:natives-linux",
                "org/lwjgl/lwjgl/1/lwjgl-1-natives-linux.jar", nativeLinux, LINUX_NATIVE_URI,
                osRules("linux")));
            libraries.add(library("org.lwjgl:lwjgl:1:natives-macos-arm64",
                "org/lwjgl/lwjgl/1/lwjgl-1-natives-macos-arm64.jar", nativeMacArm, MAC_ARM_NATIVE_URI,
                osRules("osx")));
            libraries.add(library("org.lwjgl:lwjgl-freetype:1:natives-macos-patch",
                "org/lwjgl/lwjgl-freetype/1/lwjgl-freetype-1-natives-macos-patch.jar", nativeMacPatch,
                MAC_PATCH_NATIVE_URI, osRules("osx")));
            libraries.add(library("io.netty:netty-transport-native-kqueue:1:osx-aarch_64",
                "io/netty/netty-transport-native-kqueue/1/netty-transport-native-kqueue-1-osx-aarch_64.jar",
                nettyMacArm, NETTY_MAC_ARM_URI, osRules("osx")));
            libraries.add(library("io.netty:netty-transport-native-kqueue:1:osx-x86_64",
                "io/netty/netty-transport-native-kqueue/1/netty-transport-native-kqueue-1-osx-x86_64.jar",
                nettyMacX64, NETTY_MAC_X64_URI, osRules("osx")));
            profile.add("libraries", libraries);

            JsonArray jvm = new JsonArray();
            jvm.add("-javaagent:${library_directory}/" + AGENT_PATH);
            jvm.add(conditional(osRules("windows"), "-Dwindows=true"));
            jvm.add(conditional(osRules("linux"), "-Dlinux=true"));
            jvm.add("-Djava.library.path=${natives_directory}");
            jvm.add("-cp");
            jvm.add("${classpath}");
            JsonArray game = new JsonArray();
            addPair(game, "--username", "${auth_player_name}");
            addPair(game, "--uuid", "${auth_uuid}");
            addPair(game, "--accessToken", "${auth_access_token}");
            addPair(game, "--xuid", "${auth_xuid}");
            addPair(game, "--clientId", "${clientid}");
            JsonArray resolution = new JsonArray();
            addPair(resolution, "--width", "${resolution_width}");
            addPair(resolution, "--height", "${resolution_height}");
            JsonObject resolutionRule = new JsonObject();
            JsonObject resolutionFeature = new JsonObject();
            resolutionFeature.addProperty("has_custom_resolution", true);
            JsonObject allowResolution = new JsonObject();
            allowResolution.addProperty("action", "allow");
            allowResolution.add("features", resolutionFeature);
            JsonArray resolutionRules = new JsonArray();
            resolutionRules.add(allowResolution);
            resolutionRule.add("rules", resolutionRules);
            resolutionRule.add("value", resolution);
            game.add(resolutionRule);
            if (unresolvedArgument) game.add("${unknown}");
            JsonObject arguments = new JsonObject();
            arguments.add("jvm", jvm);
            arguments.add("game", game);
            profile.add("arguments", arguments);

            JsonObject logFile = descriptor(null, logging, LOGGING_URI);
            logFile.addProperty("id", "test-log.xml");
            JsonObject loggingClient = new JsonObject();
            loggingClient.addProperty("argument", "-Dlog4j.configurationFile=${path}");
            loggingClient.add("file", logFile);
            JsonObject loggingRoot = new JsonObject();
            loggingRoot.add("client", loggingClient);
            profile.add("logging", loggingRoot);

            Path path = root.resolve("versions/LiquidCopy-1.21.11/LiquidCopy-1.21.11.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, new Gson().toJson(profile));
        }

        void download(URI uri, Path target) throws IOException {
            downloaded.add(uri);
            byte[] bytes = payloads.get(uri);
            if (bytes == null) throw new IOException("Unexpected URI " + uri);
            Files.write(target, bytes);
        }

        private static JsonObject descriptor(String path, byte[] bytes, URI uri) {
            JsonObject object = new JsonObject();
            if (path != null) object.addProperty("path", path);
            object.addProperty("sha1", Hashing.sha1(bytes));
            object.addProperty("size", bytes.length);
            object.addProperty("url", uri.toString());
            return object;
        }

        private static JsonObject library(String name, String path, byte[] bytes, URI uri, JsonArray rules) {
            JsonObject downloads = new JsonObject();
            downloads.add("artifact", descriptor(path, bytes, uri));
            JsonObject library = new JsonObject();
            library.addProperty("name", name);
            library.add("downloads", downloads);
            if (rules != null) library.add("rules", rules);
            return library;
        }

        private static JsonArray osRules(String os) {
            JsonObject operatingSystem = new JsonObject();
            operatingSystem.addProperty("name", os);
            JsonObject allow = new JsonObject();
            allow.addProperty("action", "allow");
            allow.add("os", operatingSystem);
            JsonArray rules = new JsonArray();
            rules.add(allow);
            return rules;
        }

        private static JsonObject conditional(JsonArray rules, String value) {
            JsonObject conditional = new JsonObject();
            conditional.add("rules", rules);
            conditional.addProperty("value", value);
            return conditional;
        }

        private static void addPair(JsonArray array, String first, String second) {
            array.add(first);
            array.add(second);
        }
    }

    private static final class CapturingStarter implements DirectLaunchService.ProcessStarter {
        final StubProcess process = new StubProcess();
        List<String> command;
        Path workingDirectory;
        Path logFile;

        @Override
        public Process start(List<String> command, Path workingDirectory, Path logFile) {
            this.command = List.copyOf(command);
            this.workingDirectory = workingDirectory;
            this.logFile = logFile;
            return process;
        }
    }

    private static final class StubProcess extends Process {
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
    }
}
