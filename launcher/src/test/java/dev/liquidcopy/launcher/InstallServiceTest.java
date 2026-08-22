package dev.liquidcopy.launcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallServiceTest {
    @TempDir
    Path minecraftDirectory;

    @Test
    void installsAtomicallyPreservesProfilesAndVerifiesPayload() throws Exception {
        byte[] zip = profileZip();
        byte[] payload = "embedded-agent-payload".getBytes(StandardCharsets.UTF_8);
        String originalProfiles = "{\"clientToken\":\"keep\",\"profiles\":{\"existing\":{\"lastVersionId\":\"1.21.11\"}}}";
        Files.writeString(minecraftDirectory.resolve("launcher_profiles.json"), originalProfiles);
        InstallService service = service(zip, payload, Hashing.sha1(zip));

        InstallService.InstallReport report = service.install(minecraftDirectory);

        assertTrue(Files.isRegularFile(report.baseProfile()));
        assertTrue(Files.isRegularFile(report.customProfile()));
        assertArrayEquals(payload, Files.readAllBytes(report.bootstrap()));
        assertEquals(originalProfiles, Files.readString(minecraftDirectory.resolve(InstallService.PROFILE_BACKUP_NAME)));

        JsonObject profiles = JsonParser.parseString(Files.readString(report.launcherProfiles())).getAsJsonObject();
        assertEquals("keep", profiles.get("clientToken").getAsString());
        assertTrue(profiles.getAsJsonObject("profiles").has("existing"));
        assertEquals(ProfileComposer.CUSTOM_VERSION_ID,
            profiles.getAsJsonObject("profiles").getAsJsonObject(InstallService.PROFILE_KEY)
                .get("lastVersionId").getAsString());
        assertEquals(InstallService.instanceDirectory(minecraftDirectory).toString(),
            profiles.getAsJsonObject("profiles").getAsJsonObject(InstallService.PROFILE_KEY)
                .get("gameDir").getAsString());
        assertTrue(Files.isDirectory(InstallService.instanceDirectory(minecraftDirectory)));

        InstallService.VerificationReport verification = service.verify(minecraftDirectory);
        assertTrue(verification.valid(), () -> String.join("; ", verification.messages()));
    }

    @Test
    void repairPreservesProfileCreationAndUnknownFields() throws Exception {
        byte[] zip = profileZip();
        byte[] payload = "agent".getBytes(StandardCharsets.UTF_8);
        String existing = """
            {"profiles":{"LiquidCopy-1.21.11":{
              "created":"2020-01-02T03:04:05Z",
              "lastVersionId":"broken",
              "customExtension":{"keep":true}
            }}}
            """;
        Files.writeString(minecraftDirectory.resolve("launcher_profiles.json"), existing);
        InstallService service = service(zip, payload, Hashing.sha1(zip));

        service.install(minecraftDirectory);

        JsonObject entry = JsonParser.parseString(Files.readString(minecraftDirectory.resolve("launcher_profiles.json")))
            .getAsJsonObject().getAsJsonObject("profiles").getAsJsonObject(InstallService.PROFILE_KEY);
        assertEquals("2020-01-02T03:04:05Z", entry.get("created").getAsString());
        assertTrue(entry.getAsJsonObject("customExtension").get("keep").getAsBoolean());
        assertEquals(ProfileComposer.CUSTOM_VERSION_ID, entry.get("lastVersionId").getAsString());
        assertEquals(InstallService.instanceDirectory(minecraftDirectory).toString(), entry.get("gameDir").getAsString());
    }

    @Test
    void verificationDetectsBootstrapTampering() throws Exception {
        byte[] zip = profileZip();
        InstallService service = service(zip, "agent".getBytes(StandardCharsets.UTF_8), Hashing.sha1(zip));
        InstallService.InstallReport report = service.install(minecraftDirectory);
        Files.writeString(report.bootstrap(), "tampered");

        InstallService.VerificationReport verification = service.verify(minecraftDirectory);

        assertFalse(verification.valid());
        assertTrue(verification.messages().stream().anyMatch(message -> message.contains("SHA-1 mismatch")));
    }

    @Test
    void rejectsProfileZipWhosePinnedHashDoesNotMatch() throws Exception {
        byte[] zip = profileZip();
        InstallService service = service(zip, "agent".getBytes(StandardCharsets.UTF_8), "0000000000000000000000000000000000000000");

        IOException exception = assertThrows(IOException.class, () -> service.install(minecraftDirectory));

        assertTrue(exception.getMessage().contains("SHA-1 mismatch"));
        assertFalse(Files.exists(minecraftDirectory.resolve("versions")));
    }

    private static InstallService service(byte[] zip, byte[] payload, String expectedHash) {
        return new InstallService(
            uri -> zip,
            () -> payload,
            Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC),
            "0.1.0",
            URI.create("https://example.invalid/profile.zip"),
            expectedHash
        );
    }

    private static byte[] profileZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(ProfileComposer.BASE_PROFILE_DIRECTORY + "/"
                + ProfileComposer.BASE_PROFILE_DIRECTORY + ".json"));
            zip.write(TestProfiles.baseProfile().toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
